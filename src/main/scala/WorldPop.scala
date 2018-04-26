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


import astraea.spark.rasterframes._
import com.amazonaws.services.s3.model.AmazonS3Exception
import geotrellis.proj4._
import geotrellis.spark.{TileLayerRDD, _}
import geotrellis.raster._
import geotrellis.raster.io.geotiff._
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.raster.rasterize.Rasterizer
import geotrellis.raster.reproject.{Reproject, ReprojectRasterExtent}
import geotrellis.raster.resample.{NearestNeighbor, ResampleMethod, Sum}
import geotrellis.spark.buffer.BufferedTile
import geotrellis.spark.mask._
import geotrellis.spark.mask.Mask
import geotrellis.spark.io.AttributeStore
import geotrellis.spark.reproject.TileRDDReproject
import geotrellis.spark.tiling.{LayoutDefinition, LayoutLevel, MapKeyTransform, ZoomedLayoutScheme}
import geotrellis.vector.{Extent, Polygon}
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.udf

import scala.collection.JavaConverters._
import scala.util.{Failure, Right, Success, Try}

object WorldPop {
  val CS = CellSize(0.000833300000000, 0.000833300000000)

  val Layout = LayoutDefinition(
    grid = GridExtent(Extent(-180.0000, -90.0000, 180.0000, 90.0000), CS),
    tileCols = 256, tileRows = 256)


  /**
    * Read a WorldPop raster and reproject, WebMercator at zoom 12 by default.
    * Reprojection uses "Sum" resampling method, aggregating population per cell values.
    */
  def rasterFrame(
     file: String,
     columnName: String,
     layout: LayoutDefinition = ZoomedLayoutScheme(WebMercator, 256).levelForZoom(12).layout,
     crs: CRS = WebMercator,
     masks: Traversable[Polygon] = Array.empty[Polygon]
   )(implicit spark: SparkSession): RasterFrame = {
    val (popLatLngRdd, md) = WorldPop.readBufferedTiles(file)(spark.sparkContext)

    val popRdd: TileLayerRDD[SpatialKey] = TileRDDReproject(
      bufferedTiles = popLatLngRdd,
      metadata = md,
      destCrs = crs,
      targetLayout = Right(layout),
      options = Reproject.Options(method = Sum), // add contributing population pixels
      partitioner = None
    )._2

    val masked: TileLayerRDD[SpatialKey] =
      if (masks.nonEmpty)
        popRdd.mask(masks, Mask.Options.DEFAULT.copy(
          rasterizerOptions = Rasterizer.Options(includePartial = true, sampleType = PixelIsArea)))
      else
        popRdd

    masked.toRF(columnName)
  }

  /**
    * Read TIFF into RDD, in windows that match map transform.
    */
  def readRaster(
                  file: String,
                  layout: LayoutDefinition = Layout,
                  tilesPerPartition: Int = 16)
  (implicit sc: SparkContext): TileLayerRDD[SpatialKey] = {

    val tiff: SinglebandGeoTiff = {
      val rr = Utils.rangeReader(file)
      GeoTiffReader.readSingleband(rr, decompress = false, streaming = true)
    }

    // TODO: optionally resample to match the layout cellSize
    //require(layout.cellSize == tiff.cellSize,
    //  s"Tiff ${tiff.cellSize} must match layout ${layout.cellSize}")

    val mapTransform = layout.mapTransform
    val tileBounds: GridBounds = mapTransform.extentToBounds(tiff.extent)

    val tilePixelBounds: Array[GridBounds] =
        tileBounds.coordsIter.map { case (tileCol, tileRow) =>
        val tileExtent = mapTransform.keyToExtent(tileCol, tileRow)
        val tileGridBounds = tiff.rasterExtent.gridBoundsFor(tileExtent, clamp = false)
        tileGridBounds
      }.toArray

    val partitions = tilePixelBounds.grouped(tilesPerPartition).toArray

    val tileRdd: RDD[(SpatialKey, Tile)] = sc.parallelize(partitions, partitions.length).mapPartitions { part: Iterator[Array[TileBounds]] =>
      val tiff: SinglebandGeoTiff = {
        val rr = Utils.rangeReader(file)
        GeoTiffReader.readSingleband(rr, decompress = false, streaming = true)
      }

      part.flatMap { bounds =>
        tiff.crop(bounds).map { case (pixelBounds, tile) =>
          // back-project pixel bounds to recover the tile key in the layout
          val tileExtent = tiff.rasterExtent.rasterExtentFor(pixelBounds).extent
          val layoutTileKey = mapTransform.pointToKey(tileExtent.center)
          layoutTileKey -> tile
        }.filterNot { case (key, tile) => tile.isNoDataTile }
      }
    }

    val metadata =
      TileLayerMetadata[SpatialKey](
        cellType = tiff.cellType,
        layout = layout,
        extent = tiff.extent,
        crs = tiff.crs,
        bounds = KeyBounds(mapTransform.extentToBounds(tiff.extent)))

    ContextRDD(tileRdd, metadata)
  }

  /**
    * Read TIFF into RDD of BufferedTiles for a layout.
    * This allows tiles to be reprojected without shuffle.
    */
  def readBufferedTiles(
                         file: String,
                         layout: LayoutDefinition = Layout,
                         bufferSize: Int = 3,
                         tilesPerPartition: Int = 32)
                       (implicit sc: SparkContext): (RDD[(SpatialKey, BufferedTile[Tile])], TileLayerMetadata[SpatialKey]) = {

    val tiff: SinglebandGeoTiff = {
      val rr = Utils.rangeReader(file)
      GeoTiffReader.readSingleband(rr, decompress = false, streaming = true)
    }
    val mapTransform = layout.mapTransform
    val tileBounds: GridBounds = mapTransform.extentToBounds(tiff.extent)

    val tilePixelBounds: Array[GridBounds] =
      tileBounds.coordsIter.map { case (tileCol, tileRow) =>
        val tileExtent = mapTransform.keyToExtent(tileCol, tileRow)
        val tileGridBounds = tiff.rasterExtent.gridBoundsFor(tileExtent, clamp = false)
        tileGridBounds.buffer(bufferSize, bufferSize, clamp = false)
      }.toArray

    val partitions = tilePixelBounds.grouped(tilesPerPartition).toArray

    // center pixels in BufferedTile
    val centerPixelBounds = GridBounds(
      colMin = bufferSize,
      rowMin = bufferSize,
      colMax = bufferSize + layout.tileLayout.tileCols - 1,
      rowMax = bufferSize + layout.tileLayout.tileRows - 1)

    val tileRdd: RDD[(SpatialKey, BufferedTile[Tile])] =
      sc.parallelize(partitions, partitions.length).mapPartitions { part =>
        val tiff: SinglebandGeoTiff = {
          val rr = Utils.rangeReader(file)
          GeoTiffReader.readSingleband(rr, decompress = false, streaming = true)
        }

        part.flatMap { bounds =>
          tiff
            .crop(bounds)
            .map { case (pixelBounds, tile) =>
              // back-project pixel bounds to recover the tile key in the layout
              val tileExtent = tiff.rasterExtent.rasterExtentFor(pixelBounds).extent
              val layoutTileKey = mapTransform.pointToKey(tileExtent.center)
              val adjTile = tile.convert(FloatConstantNoDataCellType)
              layoutTileKey -> BufferedTile(adjTile, centerPixelBounds)
            }.filterNot { case (key, bt) =>
              bt.tile.isNoDataTile
            }
        }
      }

    val metadata =
      TileLayerMetadata[SpatialKey](
        cellType = FloatConstantNoDataCellType,
        layout = layout,
        extent = tiff.extent,
        crs = tiff.crs,
        bounds = KeyBounds(mapTransform.extentToBounds(tiff.extent)))

    (tileRdd, metadata)
  }
}
