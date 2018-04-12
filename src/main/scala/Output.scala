package com.azavea.hotosmpopulation

import java.nio.charset.StandardCharsets

import astraea.spark.rasterframes._
import geotrellis.spark._
import geotrellis.raster._
import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import java.nio.file._

import org.apache.spark.ml.regression.LinearRegressionModel

object Output {

  def generateJson(scored: RasterFrame, model: LinearRegressionModel, path: String)(implicit spark: SparkSession): Path = {
    import spark.implicits._
    import spray.json._
    import DefaultJsonProtocol._

    val zoom = 12
    val layer = scored.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get

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
