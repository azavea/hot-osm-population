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
