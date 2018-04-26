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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import astraea.spark.rasterframes._
import geotrellis.raster.{MultibandTile, Tile}
import geotrellis.spark.{MultibandTileLayerRDD, SpatialKey}
import org.apache.spark.ml.regression.LinearRegressionModel
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.sum
import spire.syntax._

case class Result(
                   actualPopulation: Double,
                   actualOsmFootprint: Double,
                   expectedOsmFootprint: Double,
                   popCount: Int,
                   osmCount: Int)
object Result {
  def fromTile(tile: MultibandTile, popBand: Int = 0, actualOsmBand: Int = 1, expectOsmBand: Int = 2): Result = {
    def tileSum(t: Tile): (Double, Int) = {
      var sum: Double = Double.NaN
      var count: Int = 0
      import geotrellis.raster._
      t.foreachDouble { v =>
        if (isData(v) && isNoData(sum))  {
          sum = v
          count = 1
        }
        else if (isData(v)) {
          sum += v
          count += 1
        }
      }
      (sum, count)
    }
    val (actualPopulation, popCount) = tileSum(tile.band(popBand))
    val (actualOsmFootprint, osmCount) = tileSum(tile.band(actualOsmBand))
    val (expectedOsmFootprint, _) = tileSum(tile.band(expectOsmBand))

    Result(actualPopulation, actualOsmFootprint, expectedOsmFootprint, popCount, osmCount)
  }
}

object Output {
  def generateJsonFromTiles(scored_tiles: RasterFrame, model: LinearRegressionModel, path: String)(implicit spark: SparkSession): Path = {
    import spark.implicits._
    import spray.json._
    import DefaultJsonProtocol._

    val zoom = 12
    val layer = scored_tiles.toMultibandTileLayerRDD($"pop", $"osm", $"prediction").left.get

    val statsToJson = { res: Result =>
      val actualOsmOrZero = if (res.actualOsmFootprint.isNaN) 0.0 else res.actualOsmFootprint
      val expectedOsmOrZero = if (res.expectedOsmFootprint.isNaN) 0.0 else res.expectedOsmFootprint

      Map(
        "index" -> {
          JsNumber((actualOsmOrZero - expectedOsmOrZero) / expectedOsmOrZero)
        },
        "actual" -> Map(
          "pop_sum" -> res.actualPopulation,
          "osm_sum" -> actualOsmOrZero,
          "osm_avg" -> actualOsmOrZero / res.osmCount
        ).toJson,
        "prediction" -> Map(
          "osm_sum" -> expectedOsmOrZero,
          "osm_avg" -> expectedOsmOrZero / res.popCount // prediction for every pixel of population
        ).toJson
      )
    }

    val stats = layer
      .mapValues(Result.fromTile(_, 0, 1, 2))
      .collect()
      .toMap
      .map { case (SpatialKey(col, row), result) =>
        (s"$zoom/$col/$row", statsToJson(result))
      }

    val bytes = stats.toJson.compactPrint.getBytes(StandardCharsets.UTF_8)
    Files.write(Paths.get(path), bytes)
  }
}
