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


object PredictApp extends CommandApp(
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
      val downsampled = resampleRF(popWithOsm, 32, Average)
      val features = Utils.explodeTiles(downsampled, filterNaN = false)
      val scored = model.transform(features)
      val assembled = Utils.assembleTiles(scored, downsampled.tileLayerMetadata.left.get)
      Output.generateJsonFromTiles(assembled, model, outputUri)
    }
  }
)

object Output {
  /** We can generate output just by grouping and aggregating the pixels.
    * They are always associated with their source tile key.
    * This appears to be slower than reassembling the tiles
    */
  def generateJsonFromRows(scored: DataFrame, model: LinearRegressionModel, path: String)(implicit spark: SparkSession): Path = {
    import spark.implicits._
    import spray.json._
    import DefaultJsonProtocol._

    val cellsPerTile = 32*32
    val per_tile = scored
      .na.fill(0, List("pop", "osm"))
      .groupBy($"spatial_key").agg(
        sum($"pop")/cellsPerTile as "act_pop_avg",
        sum($"pop") as "act_pop_sum",
        sum($"osm")/cellsPerTile as "act_osm_avg",
        sum($"osm") as "act_osm_sum",
        sum($"prediction")/cellsPerTile as "prd_osm_avg",
        sum($"prediction") as "prd_osm_sum"
      )


    val rows = per_tile.collect()

    val rowToJson = { row: Row =>
      val key = row(0).asInstanceOf[Row]
      val spatialKey = SpatialKey(key.getInt(0), key.getInt(1))
      val act_pop_avg: Option[Double] = Option(row.getDouble(1))
      val act_pop_sum: Option[Double] = Option(row.getDouble(2))
      val act_osm_avg: Option[Double] = Option(row.getDouble(3))
      val act_osm_sum: Option[Double] = Option(row.getDouble(4))
      val prd_osm_avg: Option[Double] = Option(row.getDouble(5))
      val prd_osm_sum: Option[Double] = Option(row.getDouble(6))

      spatialKey ->
        Map(
          "index" -> {
            val buildingMean = act_osm_avg.getOrElse(0.0)
            val offset = model.intercept // intercept: 50 prediction == nothing here
            for (predictionMean <- prd_osm_avg) yield {
              (buildingMean - predictionMean) / offset
            }
          }.toJson,
          "actual" -> Map(
            "pop_sum" -> act_pop_sum,
            "osm_sum" -> act_osm_sum,
            "osm_avg" -> act_osm_avg
          ).toJson,
          "prediction" -> Map(
            "osm_sum" -> prd_osm_sum,
            "osm_avg" -> prd_osm_avg
          ).toJson
        )
    }

    val zoom = 12
    val stats =
      rows
        .map(rowToJson)
        .map { case (SpatialKey(col, row), value) =>
          (s"$zoom/$col/$row", value)
        }.toMap

    val bytes = stats.toJson.compactPrint.getBytes(StandardCharsets.UTF_8)
    Files.write(Paths.get(path), bytes)
  }

  def generateJsonFromTiles(scored_tiles: RasterFrame, model: LinearRegressionModel, path: String)(implicit spark: SparkSession): Path = {
    import spark.implicits._
    import spray.json._
    import DefaultJsonProtocol._

    val zoom = 12
    val layer = scored_tiles.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get

    val statsToJson = { mb: Array[Option[geotrellis.raster.summary.Statistics[Double]]] =>
      val pop = mb(0)
      val osm = mb(1)
      val prd = mb(2)

      Map(
        "index" -> {
          val buildingMean = osm.map(_.mean).getOrElse(0.0)
          for (prdStats <- prd) yield {
            val offset = model.intercept // intercept: 50 prediction == nothing here
            val predictionMean = prdStats.mean - offset
            (buildingMean - predictionMean) / offset
          }
        }.toJson,
        "actual" -> Map(
          "pop_sum" ->
            pop.map { stats => stats.dataCells * stats.mean },
          "osm_sum" ->
            osm.map { stats => stats.dataCells * stats.mean },
          "osm_avg" ->
            osm.map { stats => stats.mean }
        ).toJson,
        "prediction" -> Map(
          "osm_sum" ->
            prd.map { stats => stats.dataCells * stats.mean },
          "osm_avg" ->
            prd.map { stats => stats.mean }
        ).toJson
      )
    }

    val stats = layer
      .mapValues(_.statisticsDouble)
      .collect()
      .toMap
      .map { case (SpatialKey(col, row), value) =>
        (s"$zoom/$col/$row", statsToJson(value))
      }

    val bytes = stats.toJson.compactPrint.getBytes(StandardCharsets.UTF_8)
    Files.write(Paths.get(path), bytes)
  }
}
