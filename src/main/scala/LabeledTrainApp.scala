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

import astraea.spark.rasterframes._
import org.apache.spark.sql._
import astraea.spark.rasterframes._
import astraea.spark.rasterframes.ml.TileExploder
import geotrellis.proj4._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import geotrellis.vector._
import geotrellis.raster.resample._
import geotrellis.vector.io._
import geotrellis.vector.io.json._
import org.apache.spark.ml.regression._
import org.apache.spark.storage.StorageLevel
import java.nio.charset.StandardCharsets
import scala.util._
import com.monovore.decline._
import cats.implicits._

/** Trains LinearRegression on labeled good areas  */
object LabeledTrainApp extends CommandApp(
  name   = "train-osm-worldpop",
  header = "Train a regression model of OSM building footprints vs WorldPop raster for a country",
  main   = {
    val countryCodeO = Opts.option[String]("country", help = "Country code to lookup boundary from ne_50m_admin")
    val trainingPathO = Opts.option[String]("training", help = "GeoJSON file with known-good training areas")
    val worldPopUriO = Opts.option[String]("worldpop", help = "URI of WorldPop raster for a country")
    val qaTilesPathO = Opts.option[String]("qatiles", help = "Path to country QA VectorTiles mbtiles file")
    val modelUriO    = Opts.option[String]("model", help = "URI for model to be saved")

    (
      countryCodeO, worldPopUriO, qaTilesPathO, modelUriO, trainingPathO
    ).mapN { (country, worldPopUri, qaTilesPath, modelUri, trainingPath) =>

      implicit val spark: SparkSession = SparkSession.builder().
        appName("WorldPop-OSM-Train").
        master("local[*]").
        config("spark.ui.enabled", "true").
        config("spark.driver.maxResultSize", "2G").
        getOrCreate().
        withRasterFrames

      import spark.implicits._
      import Utils._

      println(s"Spark Configuration:")
      spark.sparkContext.getConf.getAll.foreach(println)

      val trainingSet: Array[Polygon] =  {
        val bytes = Files.readAllBytes(Paths.get(trainingPath))
        val json = new String(bytes, StandardCharsets.UTF_8)
        json.parseGeoJson[JsonFeatureCollection]
          .getAllMultiPolygons()
          .flatMap(_.polygons)
          .toArray
      }

      println(s"Using ${trainingSet.length} training polygons")

      // read WorldPop in WebMercator Zoom 12
      val pop: RasterFrame = WorldPop.rasterFrame(worldPopUri, "pop", masks = trainingSet)

      // Add OSM building footprints as rasterized tile column
      val popWithOsm: RasterFrame = OSM.withBuildingsRF(pop, qaTilesPath, country, "osm")

      /** OSM is way more resolute than and has much higher variance than WorldPop
        * We're going to average out both in 4x4 cells to get a tighter regression
        */
      val downsampled = resampleRF(popWithOsm, 4, Sum).persist(StorageLevel.MEMORY_AND_DISK_SER)

      // turn times into pixels so we can train on per-pixel values
      // filter out places where either WorldPop or OSM is undefined

      val features = {
        import spark.implicits._
        import org.apache.spark.ml.feature.VectorAssembler

        val exploder = new TileExploder()
        val exploded = exploder.transform(downsampled)
        // filter out population cells that are outside the mask and set all NaN OSM building pixels to 0
        // labeled training areas let us know that if we have no building footprint, there is actually no building
        val filtered = exploded.filter(! isnan($"pop")).na.fill(value = 0, List("osm"))

        val assembler = new VectorAssembler().
          setInputCols(Array("pop")).
          setOutputCol("features")

        assembler.transform(filtered)
      }

      val model = new LinearRegression().setFitIntercept(false).setLabelCol("osm").fit(features)
      model.write.overwrite.save(modelUri)

      println(s"Intercept: ${model.intercept}")
      println(s"Coefficients: ${model.coefficients}")
      println(s"rootMeanSquaredError: ${model.summary.rootMeanSquaredError}")
    }
  }
)

