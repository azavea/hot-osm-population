package com.azavea.hotosmpopulation


import astraea.spark.rasterframes._
import com.amazonaws.services.s3.model.AmazonS3Exception
import geotrellis.proj4._
import geotrellis.spark._
import geotrellis.raster._
import geotrellis.raster.io.geotiff._
import geotrellis.raster.reproject.Reproject
import geotrellis.raster.resample.Sum
import geotrellis.spark.buffer.BufferedTile
import geotrellis.spark.io.AttributeStore
import geotrellis.spark.reproject.TileRDDReproject
import geotrellis.spark.tiling.{LayoutDefinition, LayoutLevel, MapKeyTransform, ZoomedLayoutScheme}
import geotrellis.vector.Extent
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
     crs: CRS = WebMercator
   )(implicit spark: SparkSession): RasterFrame = {
    val (popLatLngRdd, md) = WorldPop.readBufferedTiles(file)(spark.sparkContext)

    val popRdd = TileRDDReproject(
      bufferedTiles = popLatLngRdd,
      metadata = md,
      destCrs = crs,
      targetLayout = Right(layout),
      options = Reproject.Options(method = Sum), // add contributing population pixels
      partitioner = None
    )._2

    popRdd.toRF(columnName)
  }

  /**
    * Augment a RasterFrame with a column that contains rasterized OSM building footprint by level.
    * It is required that the RasterFrame key is from WebMercator TMS layout at zoom 12.
    */
  def withOSMBuildings(rf: RasterFrame, countryCode: String, columnName: String)(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._

    @transient lazy val generator = FootprintGenerator ("osmesa", "vectortiles", countryCode)

    val md = rf.tileLayerMetadata.left.get

    // this layout level is required to find the vector tiles
    val layoutLevel = ZoomedLayoutScheme(WebMercator, 256).levelForZoom(12)
    val fetchOSMTile = udf { (col: Int, row: Int) =>
      Try (generator (SpatialKey(col, row), layoutLevel, "history")) match {

        case Success(raster) =>
          raster.tile: Tile

        case Failure(_: AmazonS3Exception) =>
          FloatConstantTile(Float.NaN, md.layout.tileCols, md.layout.tileRows): Tile

        case Failure(e) =>
          throw e // this is probably bad, sound the bells
      }
    }

    rf.withColumn(columnName, fetchOSMTile($"spatial_key.col", $"spatial_key.row")).asRF
  }

  /**
    * Read TIFF into RDD, in windows that match map transform.
    */
  def readRaster(
                  file: String,
                  layout: LayoutDefinition = Layout,
                  tilesPerPartition: Int = 32)
  (implicit sc: SparkContext): TileLayerRDD[SpatialKey] = {

    val tiff = SinglebandGeoTiff(file, decompress = false, streaming = true)

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

    val tileRdd: RDD[(SpatialKey, Tile)] = sc.parallelize(partitions, partitions.length).mapPartitions { part =>
      val tiff = SinglebandGeoTiff(file, decompress = false, streaming = true)
      tiff.crop(tilePixelBounds).map { case (pixelBounds, tile) =>
        // back-project pixel bounds to recover the tile key in the layout
        val tileExtent = tiff.rasterExtent.rasterExtentFor(pixelBounds).extent
        val layoutTileKey = mapTransform.pointToKey(tileExtent.center)
        layoutTileKey -> tile
      }.filterNot { case (key, tile) => tile.isNoDataTile }
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

    val tiff = SinglebandGeoTiff(file, decompress = false, streaming = true)
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
        val tiff = SinglebandGeoTiff(file, decompress = false, streaming = true)

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
