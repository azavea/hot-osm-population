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

import com.monovore.decline._
import cats.implicits._

object TrainApp extends CommandApp(
  name   = "train-osm-worldpop",
  header = "Train a regression model of OSM building footprints vs WorldPop raster for a country",
  main   = {
    val countryCodeO = Opts.option[String]("country", help = "Country code to lookup boundary from ne_50m_admin")
    val worldPopUriO = Opts.option[String]("worldpop", help = "URI of WorldPop raster for a country")
    val qaTilesPathO = Opts.option[String]("qatiles", help = "Path to country QA VectorTiles mbtiles file")
    val modelUriO    = Opts.option[String]("model", help = "URI for model to be saved")

    (
      countryCodeO, worldPopUriO, qaTilesPathO, modelUriO
    ).mapN { (countryCode, worldPopUri, qaTilesPath, modelUri) =>

      implicit val spark: SparkSession = SparkSession.builder().
        appName("WorldPop-OSM-Training").
        master("local[*]").
        config("spark.ui.enabled", "true").
        config("spark.driver.maxResultSize", "2G").
        getOrCreate().
        withRasterFrames

      import spark.implicits._
      import Utils._

      println(s"Spark Configuration:")
      spark.sparkContext.getConf.getAll.foreach(println)

      // read WorldPop in WebMercator Zoom 12
      val pop: RasterFrame = WorldPop.rasterFrame(worldPopUri, "pop")

      // Add OSM building footprints as rasterized tile column
      val popWithOsm: RasterFrame = WorldPop.withOSMBuildings(pop, qaTilesPath, countryCode, "osm")

      // We will have to do an IO step, a shuffle and IO, lets cache the result
      popWithOsm.persist(StorageLevel.MEMORY_AND_DISK_SER)

      /** OSM is way more resolute than and has much higher variance than WorldPop
        * We're going to average out both in 8x8 cells to get a tighter regression
        */
      val downsampled = resampleRF(popWithOsm, 32, Average)

      // turn times into pixels so we can train on per-pixel values
      // filter out places where either WorldPop or OSM is undefined
      val features = Utils.explodeTiles(downsampled, filterNaN = true)

      val model = new LinearRegression().setFitIntercept(true).setLabelCol("osm").fit(features)
      model.save(modelUri)

      /** If we want to verify the model output we can save it as GeoTiff */
      //val scored = model.transform(explodeTiles(downsampled, filterNaN = false))
      //val scored_tiles = assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
      //saveCog(
      //  rdd = scored_native.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get,
      //  catalog = "/hot-osm/cog", name ="BWA15v4-prediction-avg", zooms = (12,6))
    }
  }
)

object Train {

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