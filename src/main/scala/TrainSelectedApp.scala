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

import java.nio.file.{Files, Paths}

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
import geotrellis.vector.io._
import geotrellis.vector.io.json._
import org.apache.spark.Partitioner
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.regression._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.gt.types.TileUDT
import org.apache.spark.storage.StorageLevel
import java.nio.charset.StandardCharsets

import scala.util._
import com.monovore.decline._
import cats.implicits._
import geotrellis.raster.rasterize.Rasterizer
import geotrellis.vector.{GeometryCollection, MultiPolygon, Polygon}
import org.geotools.feature.FeatureCollection

/** Trains LinearRegression on labeled good areas  */
object TrainSelectedApp extends CommandApp(
  name   = "train-selected-osm-worldpop",
  header = "Train a regression model of OSM building footprints vs WorldPop raster for a country",
  main   = {
    val countryCodeO = Opts.option[String]("country", help = "Country code to lookup boundary from ne_50m_admin")
    val trainingPathO = Opts.option[String]("training", help = "GeoJSON file with known-good training areas")
    val worldPopUriO = Opts.option[String]("worldpop", help = "URI of WorldPop raster for a country")
    val qaTilesPathO = Opts.option[String]("qatiles", help = "Path to country QA VectorTiles mbtiles file")
    val modelUriO    = Opts.option[String]("model", help = "URI for model to be saved")

    (
      countryCodeO, worldPopUriO, qaTilesPathO, modelUriO, trainingPathO
    ).mapN { (countryCode, worldPopUri, qaTilesPath, modelUri, trainingPath) =>

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

      val trainingAreas: Array[Polygon] =  {
        val bytes = Files.readAllBytes(Paths.get(trainingPath))
        val json = new String(bytes, StandardCharsets.UTF_8)
        json.parseGeoJson[JsonFeatureCollection]
          .getAllMultiPolygons()
          .flatMap(_.polygons)
          .toArray
      }

      // read WorldPop in WebMercator Zoom 12
      val pop: RasterFrame = WorldPop.rasterFrame(worldPopUri, "pop", masks = trainingAreas)

      // Add OSM building footprints as rasterized tile column
      val popWithOsm: RasterFrame = OSM.withBuildingsRF(pop, qaTilesPath, countryCode, "osm")


      // We will have to do an IO step, a shuffle and IO, lets cache the result
      popWithOsm.persist(StorageLevel.MEMORY_AND_DISK_SER)

      /** OSM is way more resolute than and has much higher variance than WorldPop
        * We're going to average out both in 8x8 cells to get a tighter regression
        */
      val downsampled = resampleRF(popWithOsm, 8, Sum)

      // turn times into pixels so we can train on per-pixel values
      // filter out places where either WorldPop or OSM is undefined
      val features = {
        import spark.implicits._
        import org.apache.spark.ml.feature.VectorAssembler

        val exploder = new TileExploder()
        val exploded = exploder.transform(downsampled)
        // filter out population cells that are outside the mask and set all NaN OSM building pixels to 0
        // labeled training areas let us know that if we have no building footprint, there is actually no building
        val filtered = exploded.filter(! isnan($"pop")).na.fill(value =0, List("osm"))

        val assembler = new VectorAssembler().
          setInputCols(Array("pop")).
          setOutputCol("features")

        assembler.transform(filtered)
      }

      val model = new LinearRegression().setFitIntercept(false).setLabelCol("osm").fit(features)
      model.save(modelUri)

      /** If we want to verify the model output we can save it as GeoTiff */
      val scored = model.transform(explodeTiles(downsampled, filterNaN = false))
      val scored_tiles = assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
      saveCog(
        rdd = scored_tiles.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get,
        catalog = "/hot-osm/cog", name ="BWA15v4-prediction-sum", zooms = (12,6))
    }
  }
)

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import astraea.spark.rasterframes._
import geotrellis.spark._
import geotrellis.raster._
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import java.nio.file._

import cats.implicits._
import com.monovore.decline._
import geotrellis.raster.resample.Average
import geotrellis.spark.io.hadoop.HdfsUtils
import org.apache.hadoop.fs
import org.apache.spark.ml.regression.LinearRegressionModel
import org.apache.spark.storage.StorageLevel
import spire.syntax.cfor._

object PredictSelectedApp extends CommandApp(
  name   = "predict-osm-worldpop",
  header = "Predict OSM building density from WorldPop",
  main   = {
    val worldPopUriO = Opts.option[String]("worldpop", help = "URI of WorldPop raster for a country")
    val qaTilesPathO = Opts.option[String]("qatiles", help = "Path to country QA VectorTiles mbtiles file")
    val countryCodeO = Opts.option[String]("country", help = "Country code to lookup boundary from ne_50m_admin")
    val modelUriO    = Opts.option[String]("model", help = "URI for model to be saved")
    val outputUriO   = Opts.option[String]("output", help = "URI for JSON output")

    (
      worldPopUriO, qaTilesPathO, countryCodeO, modelUriO, outputUriO
    ).mapN { (worldPopUri, qaTilesPath, countryCode, modelUri, outputUri) =>

      implicit val spark: SparkSession = SparkSession.builder().
        appName("WorldPop-OSM-Predict").
        master("local[*]").
        config("spark.ui.enabled", "true").
        config("spark.driver.maxResultSize", "2G").
        getOrCreate().
        withRasterFrames

      import spark.implicits._
      import Utils._

      println(s"Spark Configuration:")
      spark.sparkContext.getConf.getAll.foreach(println)

      val model = LinearRegressionModel.load(modelUri)

      val pop: RasterFrame = WorldPop.rasterFrame(worldPopUri, "pop")
      val popWithOsm: RasterFrame = OSM.withBuildingsRF(pop, qaTilesPath, countryCode, "osm")
      val downsampled = resampleRF(popWithOsm, 8, Sum)

      val features = Utils.explodeTiles(downsampled, filterNaN = false)
      val scored = model.transform(features)
      val assembled = Utils.assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
      saveCog(
        rdd = assembled.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get,
        catalog = "/hot-osm/cog", name ="BWA15v4-prediction-sum-out", zooms = (12,6))

      Output.generateJsonFromTiles(assembled, model, outputUri)
    }
  }
)