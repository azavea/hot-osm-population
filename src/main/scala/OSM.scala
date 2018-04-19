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
import geotrellis.proj4._
import geotrellis.spark._
import geotrellis.raster._
import geotrellis.spark.tiling.{LayoutDefinition, LayoutLevel, MapKeyTransform, ZoomedLayoutScheme}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.udf

object OSM {
  def buildingsRF(qaTilesPath: String, countryCode: String, layer: String = "osm")(implicit spark: SparkSession): RasterFrame = {
    val layout = ZoomedLayoutScheme(WebMercator, 12).levelForZoom(12).layout
    val countryBound = CountryGeometry(countryCode).get.geom.reproject(LatLng, WebMercator)
    val keys = layout.mapTransform.keysForGeometry(countryBound).toArray
    val partitions: Array[Array[SpatialKey]] = keys.grouped(64).toArray

    val rdd: RDD[(SpatialKey, Tile)] =
      spark.sparkContext.parallelize(partitions, partitions.length).mapPartitions { part =>
        val layout = ZoomedLayoutScheme(WebMercator, 12).levelForZoom(12)
        val generator = FootprintGenerator (qaTilesPath, countryCode)
        part.flatMap { _.map { key: SpatialKey =>
          val tile = generator(key, layout, "osm").tile
          key -> tile
        }.filterNot { case (_, tile) =>
          tile.isNoDataTile
        }}
      }

    val extent = countryBound.envelope
    val metadata = TileLayerMetadata[SpatialKey](
      cellType = FloatConstantNoDataCellType,
      crs = WebMercator,
      extent = extent,
      layout = layout,
      bounds = KeyBounds(layout.mapTransform.extentToBounds(extent)))

    ContextRDD(rdd, metadata).toRF(layer)
  }

  /**
    * Augment a RasterFrame with a column that contains rasterized OSM building footprint by level.
    * It is required that the RasterFrame key is from WebMercator TMS layout at zoom 12.
    */
  def withBuildingsRF(rf: RasterFrame, qaTiles: String, countryCode: String, columnName: String)(implicit spark: SparkSession): RasterFrame = {
    import spark.implicits._

    @transient lazy val generator = FootprintGenerator (qaTiles, countryCode)

    val md = rf.tileLayerMetadata.left.get

    // this layout level is required to find the vector tiles
    val layoutLevel = ZoomedLayoutScheme(WebMercator, 256).levelForZoom(12)
    val fetchOSMTile = udf { (col: Int, row: Int) =>
      generator (SpatialKey(col, row), layoutLevel, "osm").tile
    }

    rf.withColumn(columnName, fetchOSMTile($"spatial_key.col", $"spatial_key.row")).asRF
  }

}
