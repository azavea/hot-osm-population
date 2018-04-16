package com.azavea.hotosmpopulation

import java.nio.file.{Files, Paths}

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.rasterize._
import geotrellis.spark._
import geotrellis.spark.tiling._
import geotrellis.vector._
import geotrellis.vector.voronoi._
import geotrellis.vectortile._
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.vividsolutions.jts
import com.vividsolutions.jts.geom.TopologyException
import com.vividsolutions.jts.geom.prep.PreparedPolygon
import org.apache.commons.io.IOUtils
import spire.syntax.cfor._

import scala.util._

case class FootprintGenerator(qaTilesPath: String, country: String, tileCrs: CRS = WebMercator) {
  val africaAEA = CRS.fromString("+proj=aea +lat_1=20 +lat_2=-23 +lat_0=0 +lon_0=25 +x_0=0 +y_0=0 +ellps=WGS84 +datum=WGS84 +units=m +no_defs")

  val countryBound: MultiPolygon = CountryGeometry(country) match {
    case Some(feat) => feat.geom.reproject(LatLng, tileCrs)
    case None => throw new MatchError(s"Country code $country did not match")
  }

  val mbtiles = new MBTiles(qaTilesPath, ZoomedLayoutScheme(WebMercator))

  /** Retrieve building features from a vectortile store */
  def fetchBuildings(key: SpatialKey, ll: LayoutLevel, layer: String = "osm"): Seq[Feature[Polygon, Map[String, Value]]] = {
    mbtiles.fetch(12, key.col, key.row) match {
      case Some(vt) =>
        vt.layers(layer)
          .polygons
          .filter{ feat => feat.data.contains("building") }
        // && (feat.data("building") == VBool(true) || feat.data("building") == VString("yes") || feat.data("building") == VString("residential")) }

      case None =>
        Seq.empty
    }
  }

  /** Generate building polygons with corresponding number of levels
   */
  def buildingPolysWithLevels(key: SpatialKey, layout: LayoutLevel, layer: String): Seq[(Polygon, Double)] = {
    val buildings = fetchBuildings(key, layout, layer)
    val gpr = new jts.precision.GeometryPrecisionReducer(new jts.geom.PrecisionModel)

    buildings.flatMap{ feat =>
      val poly = feat.geom
      val validated = if (!poly.isValid) { gpr.reduce(poly.jtsGeom) } else poly.jtsGeom
      val levels = feat.data.get("building:levels") match {
        case Some(VString(s)) => Try(s.toDouble).toOption.getOrElse(Double.NaN)
        case Some(VInt64(l)) => l.toDouble
        case Some(VSint64(l)) => l.toDouble
        case Some(VWord64(l)) => l.toDouble
        case Some(VFloat(f)) => f.toDouble
        case Some(VDouble(d)) => d
        case _ => 1.0
      }
      validated match {
        case p: jts.geom.Polygon if !p.isEmpty=>
          Seq( (Polygon(p), levels) )
        case mp: jts.geom.MultiPolygon =>
          MultiPolygon(mp).polygons.toSeq.map{ p => (p, levels) }
        case _ => Seq.empty
      }
    }
  }

  /** Produce a raster giving the total square footage of buildings per pixel in a given
   *  spatial key with a given layout
   */
  def apply(key: SpatialKey, layout: LayoutLevel, layer: String = "osm"): Raster[Tile] = {
    val LayoutLevel(_, ld) = layout
    val tileExtent = ld.mapTransform(key)

    val raster: Raster[FloatArrayTile] = Raster(FloatArrayTile.empty(ld.tileCols, ld.tileRows), tileExtent)
    val re = raster.rasterExtent

    // compute land area of pixel using an equal area projection for Africa (assumes that this application will focus on African use-cases)
    val cellArea = tileExtent.toPolygon.reproject(tileCrs, africaAEA).area * re.cellwidth * re.cellheight / re.extent.area

    buildingPolysWithLevels(key, layout, layer)
      .foreach{ case (poly, levels) => {
        try {
          polygon.FractionalRasterizer.foreachCellByPolygon(poly, re)(new FractionCallback {
            def callback(col: Int, row: Int, frac: Double) = {
              if (col >= 0 && col < re.cols && row >= 0 && row < re.rows) {
                val p = raster.tile.getDouble(col, row)
                val v = frac * cellArea * levels + (if (isNoData(p)) 0.0 else p)
                raster.tile.setDouble(col, row, v)
              }
            }
          })
        } catch {
          case e: ArrayIndexOutOfBoundsException =>
            println(s"ERROR: ArrayIndexOutOfBoundsException in $key")

          case e: TopologyException =>
            println(s"ERROR: TopologyException in $key: ${e.getMessage}")
        }
      }}

    raster
  }

  /** Produce a building density raster using a k-nearest neighbors weighted density estimate
   *
   * At present, this function is not recommended to be used in actual analysis.
   */
  def buildingDensity(key: SpatialKey, layout: LayoutLevel, k: Int = 25, layer: String = "history"): Raster[FloatArrayTile] = {
    val LayoutLevel(zoom, ld) = layout
    val tileExtent = ld.mapTransform(key)

    val weightedPts = buildingPolysWithLevels(key, layout, layer).map{ case (poly, levels) => {
      (poly.centroid.as[Point].get, levels * poly.area)
    }}

    val index = SpatialIndex(weightedPts){ case (p, _) => (p.x, p.y) }

    val raster = Raster(FloatArrayTile.empty(ld.tileCols, ld.tileRows), tileExtent)
    val re = raster.rasterExtent

    println(s"Center of extent: ${re.extent.centroid}")
    cfor(0)(_ < ld.tileCols, _ + 1){ col =>
      cfor(0)(_ < ld.tileRows, _ + 1){ row =>
        val (x, y) = re.gridToMap(col, row)
        if (col == 128 && row == 128) {
          println(s"($col, $row) -> ($x, $y)")
        }
        val knn = index.kNearest(x, y, k)
        val weights = knn.map(_._2).reduce(_+_)
        val r = knn.map(_._1.distance(Point(x, y))).max
        raster.tile.setDouble(col, row, weights / (math.Pi * r * r))
      }
    }

    raster
  }

  /** Return the total square-meter coverage of buildings in a given spatial key.
   */
  def buildingArea(key: SpatialKey, layout: LayoutLevel, layer: String = "history") = {
    val buildings = apply(key, layout, layer)

    var accum = 0.0
    var count = 0
    countryBound.foreach(buildings.rasterExtent, Rasterizer.Options(true, PixelIsArea)){ (x: Int, y: Int) =>
      val v = buildings.getDouble(x, y)
      if (!v.isNaN) accum += v
      count += 1
    }

    (accum, count)
  }

}
