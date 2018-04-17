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
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

import cats.effect.IO
import doobie._
import doobie.implicits._
import geotrellis.spark.tiling.ZoomedLayoutScheme
import geotrellis.vectortile.VectorTile

case class ResTile(zoom: Int, col: Int, row: Int, pbf: Array[Byte])

class MBTiles(dbPath: String, scheme: ZoomedLayoutScheme) {
  val xa = Transactor.fromDriverManager[IO](
    "org.sqlite.JDBC",
    s"jdbc:sqlite:$dbPath",
    "", ""
  )

  def fetch(zoom: Int, col: Int, row: Int): Option[VectorTile] = {
    // https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md#content-1
    val flipRow = (1<<zoom) - 1 - row
    find(zoom, col, flipRow).transact(xa).unsafeRunSync.map { tile =>
      val extent = scheme.levelForZoom(zoom).layout.mapTransform.keyToExtent(col, row)
      val is = new ByteArrayInputStream(tile.pbf)
      val gzip = new GZIPInputStream(is)
      val bytes = sun.misc.IOUtils.readFully(gzip, -1, true)
      VectorTile.fromBytes(bytes, extent)
    }
  }

  def all(zoom: Int): Seq[VectorTile] = {
    findAll.transact(xa).unsafeRunSync.map { tile =>
      val extent = scheme.levelForZoom(zoom).layout.mapTransform.keyToExtent(tile.col, tile.row)
      val is = new ByteArrayInputStream(tile.pbf)
      val gzip = new GZIPInputStream(is)
      val bytes = sun.misc.IOUtils.readFully(gzip, -1, true)
      VectorTile.fromBytes(bytes, extent)
    }
  }

  private def find(zoom: Int, col: Int, row: Int): ConnectionIO[Option[ResTile]] =
    sql"""
    select zoom_level, tile_column, tile_row, tile_data
    from tiles
    where zoom_level=$zoom and tile_column=$col and tile_row=$row
    """.query[ResTile].option

  private def findAll: ConnectionIO[List[ResTile]] =
    sql"""
    select zoom_level, tile_column, tile_row, tile_data
    from tiles
    """.query[ResTile].to[List]
}
