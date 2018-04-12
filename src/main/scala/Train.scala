package com.azavea.hotosmpopulation

import astraea.spark.rasterframes.StandardColumns.SPATIAL_KEY_COLUMN
import astraea.spark.rasterframes._
import astraea.spark.rasterframes.ml.TileExploder
import com.amazonaws.services.s3.model.AmazonS3Exception
import geotrellis.proj4.{CRS, LatLng, WebMercator}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.json._
import geotrellis.spark.reproject._
import geotrellis.raster._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster.io.geotiff.compression.NoCompression
import geotrellis.raster.resample._
import geotrellis.spark.buffer.BufferedTile
import geotrellis.spark.io.LayerWriter
import geotrellis.spark.io.cog.COGLayer
import geotrellis.spark.io.file.FileAttributeStore
import geotrellis.spark.io.file.cog.{FileCOGLayerReader, FileCOGLayerWriter}
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.partition.PartitionerIndex.SpatialPartitioner
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.reproject.Reproject.Options
import geotrellis.spark.tiling._
import org.apache.spark.Partitioner
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.regression._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.gt.types.TileUDT
import org.apache.spark.storage.StorageLevel
import scala.util._

object Train {

  /** Main Training script */
  def script(implicit spark: SparkSession): Unit = {
    import spark.implicits._
    import Utils._

    // read WorldPop in WebMercator Zoom 12
    val pop: RasterFrame = WorldPop.rasterFrame(
      "/tmp/WorldPop/BWA15v4.tif", "pop")

    val popWithOsm: RasterFrame = WorldPop.withOSMBuildings(pop, "BWA", "osm")
    popWithOsm.persist(StorageLevel.MEMORY_AND_DISK_SER)

    val downsampled = resampleRF(popWithOsm, 32, Average).withSpatialIndex()
    val features = Utils.explodeTiles(downsampled, filterNaN = true)

    val model = new LinearRegression().setFitIntercept(true).setLabelCol("osm").fit(features)
    model.save("/tmp/hot-osm/models/BWA15-avg-32")
    val scored = model.transform(explodeTiles(downsampled, filterNaN = false))

    val scored_tiles = assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
    val scored_native = resampleRF(scored_tiles, 256, NearestNeighbor)

    saveCog(
      rdd = scored_native.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get,
      catalog = "/tmp/hot-cog", name ="BWA15-prediction-avg", zooms = (12,8))
  }


  def readWorldPopCog(catalog: String, layer: String)(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._
    val attributeStore = FileAttributeStore(catalog)
    val reader = FileCOGLayerReader(attributeStore)(spark.sparkContext)

    val layoutScheme = ZoomedLayoutScheme (WebMercator, 256)
    val layoutLevel = layoutScheme.levelForZoom (12)
    val layout = layoutLevel.layout

    val rdd = reader.read[SpatialKey, MultibandTile](
      id = LayerId(layer, layoutLevel.zoom),
      numPartitions = 64)

    val rf = rdd.toRF(PairRDDConverter.forSpatialMultiband(3))
    rf.select(rf.spatialKeyColumn, $"band_0" as "pop", $"band_1" as "osm").asRF
  }
}
