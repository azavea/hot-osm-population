/*
 * Copyright 2018 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.azavea.hotosmpopulation

import java.net.URI
import java.nio.file.Paths

import astraea.spark.rasterframes._
import astraea.spark.rasterframes.ml.TileExploder
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3URI
import geotrellis.raster.{FloatConstantNoDataCellType, MultibandTile, Tile}
import geotrellis.raster.io.geotiff.compression.NoCompression
import geotrellis.raster.resample.{ResampleMethod, Sum}
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.cog.COGLayer
import geotrellis.spark.io.file.FileAttributeStore
import geotrellis.spark.io.file.cog.{FileCOGLayerReader, FileCOGLayerWriter}
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.s3.AmazonS3Client
import geotrellis.spark.io.s3.util.S3RangeReader
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.tiling.LayoutScheme
import geotrellis.util.{FileRangeReader, RangeReader}
import org.apache.spark.sql.functions.{isnan, udf}
import org.apache.spark.sql.gt.types.TileUDT
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import com.amazonaws.services.s3.{AmazonS3URI, AmazonS3Client => AWSAmazonS3Client}

import scala.reflect.ClassTag

object Utils {

  def rangeReader(uri: String): RangeReader = {
    val javaUri = new URI(uri)
    javaUri.getScheme match {
      case "file" | null =>
        FileRangeReader(Paths.get(javaUri).toFile)

      case "s3" =>
        val s3Uri = new AmazonS3URI(java.net.URLDecoder.decode(uri, "UTF-8"))
        val s3Client = new AmazonS3Client(new AWSAmazonS3Client(new DefaultAWSCredentialsProviderChain))
        S3RangeReader(s3Uri.getBucket, s3Uri.getKey, s3Client)

      case scheme =>
        throw new IllegalArgumentException(s"Unable to read scheme $scheme at $uri")
    }
  }

  /** Resample each tile in RasterFrame to new resolution.
    * This method does not sample past tile boundary, which restricts the choice of ResampleMethods.
    */
  def resampleRF(rf: RasterFrame, pixels: Int, method: ResampleMethod)(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._

    val resampleTile = udf { tile: Tile => tile.resample(pixels, pixels, method) }

    val columns =
      rf.schema.fields.map {
        case field if field.dataType.typeName.equalsIgnoreCase(TileUDT.typeName) =>
          resampleTile(rf.col(field.name)) as field.name
        case field =>
          rf.col(field.name)
      }

    val md = rf.tileLayerMetadata.left.get
    val tl = md.layout.tileLayout.copy(
      tileCols = pixels,
      tileRows = pixels)
    val resampleMd = md.copy(layout = md.layout.copy(tileLayout = tl))

    rf.select(columns: _*).asRF(rf.spatialKeyColumn, resampleMd)
  }

  /** Resample each tile in TileLayerRDD to new resolution.
    * This method does not sample past tile boundary, which restricts the choice of ResampleMethods.
    */
  def resampleLayer[K: ClassTag](layer: MultibandTileLayerRDD[K], size: Int, method: ResampleMethod): MultibandTileLayerRDD[K] = {
    layer.withContext {
      _.mapValues { mb => mb.resample(size, size, method) }
    }.mapContext { md: TileLayerMetadata[K] =>
      val tl = md.tileLayout.copy(tileCols = size, tileRows = size)
      md.copy(layout = md.layout.copy(tileLayout = tl))
    }
  }

  /** Explode RasterFrame tiles into its pixels and "features" vector.
    * This function is specific to the WorldPop-OSM use case.
    */
  def explodeTiles(rf: RasterFrame, filterNaN: Boolean = true)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    import org.apache.spark.ml.feature.VectorAssembler

    val exploder = new TileExploder()
    val exploded = exploder.transform(rf)

    val filtered = if (filterNaN)
      exploded.filter(! isnan($"osm") && ! isnan($"pop"))
    else
      exploded

    val assembler = new VectorAssembler().
      setInputCols(Array("pop")).
      setOutputCol("features")

    assembler.transform(filtered)
  }

  /** Re-assemble pixel values into tiles from "pop", "osm" and "prediction" columns into bands.
    * This function is specific to the WorldPop-OSM use case.
    */
  def assembleTiles(scored: DataFrame, tlm: TileLayerMetadata[SpatialKey])(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._

    scored.groupBy($"spatial_key").agg(
      assembleTile(
        $"column_index", $"row_index", $"pop",
        tlm.tileCols, tlm.tileRows, FloatConstantNoDataCellType
      ),
      assembleTile(
        $"column_index", $"row_index", $"osm",
        tlm.tileCols, tlm.tileRows, FloatConstantNoDataCellType
      ),
      assembleTile(
        $"column_index", $"row_index", $"prediction",
        tlm.tileCols, tlm.tileRows, FloatConstantNoDataCellType
      )
    ).asRF
  }

  /** Save MultibandTileLayerRDD as a GeoTrellis COG layer for debuging.
    * This makes it super easy to load into QGIS and look around.
    */
  def saveCog(rdd: MultibandTileLayerRDD[SpatialKey], catalog: String, name: String, zooms: (Int, Int))(implicit spark: SparkSession): Unit = {
    val attributeStore = FileAttributeStore(catalog)
    val writer = FileCOGLayerWriter(attributeStore)

    val (zoom, minZoom) = zooms
    val cogLayer =
      COGLayer.fromLayerRDD(
        rdd,
        zoom,
        compression = NoCompression,
        maxTileSize = 4096,
        minZoom = Some(minZoom) // XXX: this doesn't really do anything
      )

    val keyIndexes =
      cogLayer.metadata.zoomRangeInfos.
        map { case (zr, bounds) => zr -> ZCurveKeyIndexMethod.createIndex(bounds) }.
        toMap

    writer.writeCOGLayer(name, cogLayer, keyIndexes)
  }

  /** Pyramid and save RasterFrame as GeoTrellis Avro layer */
  def savePyramid(rf: RasterFrame, layoutScheme: LayoutScheme, col: Column, catalog: String, name: String)(implicit spark: SparkSession): Unit = {
    val rdd: TileLayerRDD[SpatialKey] = rf.toTileLayerRDD(col).left.get
    val writer = LayerWriter(catalog)
    val store = writer.attributeStore
    val hist = rdd.histogram(100)

    Pyramid.upLevels(rdd, layoutScheme, 12, Sum) { (rdd, zoom) =>
      val id = LayerId(name, zoom)
      if (store.layerExists(id)) store.delete(id)
      writer.write(id, rdd, ZCurveKeyIndexMethod)
    }
    import geotrellis.raster.io.json._
    import geotrellis.raster.io._
    store.write(LayerId(name, 0), "histogram", hist)
  }


  /** Read a single band from GeoTrellis COG layer into RasterFrame */
  def readCogBand(catalog: String, layer: String, band: Int, col: String)(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._
    val attributeStore = FileAttributeStore(catalog)
    val reader = FileCOGLayerReader(attributeStore)(spark.sparkContext)
    val rdd = reader.read[SpatialKey, MultibandTile](
      id = LayerId(layer, 12),
      numPartitions = 64)

    rdd.withContext { _.mapValues(_.band(band)) }.toRF(col)
  }
}
