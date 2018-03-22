package com.azavea.hotosmpopulation

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.rasterize._
import geotrellis.spark._
import geotrellis.spark.tiling._
import geotrellis.vector._
import geotrellis.vectortile._

import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.vividsolutions.jts
import org.apache.commons.io.IOUtils

case class FootprintGenerator(bucket: String, prefix: String, tileCrs: CRS = WebMercator) {

  private val s3client = AmazonS3ClientBuilder.defaultClient

  def fetchBuildings(layer: String, key: SpatialKey, ll: LayoutLevel) = {
    val LayoutLevel(zoom, ld) = ll
    val tileExtent = ld.mapTransform(key)

    val s3object = s3client.getObject(bucket, s"$prefix/$zoom/${key._1}/${key._2}.mvt")
    val vtbuffer = IOUtils.toByteArray(s3object.getObjectContent)

    VectorTile.fromBytes(vtbuffer, tileExtent)
      .layers(layer)
      .polygons
      .filter{ feat => feat.data.contains("building") } // && (feat.data("building") == VBool(true) || feat.data("building") == VString("yes") || feat.data("building") == VString("residential")) }
  }

  def apply(layer: String, key: SpatialKey, layout: LayoutLevel) = {
    val LayoutLevel(zoom, ld) = layout
    val tileExtent = ld.mapTransform(key)

    val buildings = fetchBuildings(layer, key, layout)

    val raster = Raster(FloatArrayTile.empty(ld.tileCols, ld.tileRows), tileExtent)
    val re = raster.rasterExtent
    // use an equal area projection for Africa (assuming that this application will focus on African use-cases
    val africaAEA = CRS.fromString("+proj=aea +lat_1=20 +lat_2=-23 +lat_0=0 +lon_0=25 +x_0=0 +y_0=0 +ellps=WGS84 +datum=WGS84 +units=m +no_defs")
    val cellArea = tileExtent.toPolygon.reproject(tileCrs, africaAEA).area * re.cellwidth * re.cellheight / re.extent.area

    val gpr = new jts.precision.GeometryPrecisionReducer(new jts.geom.PrecisionModel)

    buildings
      .foreach{ feat => {
        val poly = feat.geom
        val validated = if (!poly.isValid) { gpr.reduce(poly.jtsGeom) } else poly.jtsGeom

        val levels = feat.data.get("building:levels") match {
          case Some(VString(s)) => s.toDouble
          case Some(VInt64(l)) => l.toDouble
          case Some(VSint64(l)) => l.toDouble
          case Some(VWord64(l)) => l.toDouble
          case Some(VFloat(f)) => f.toDouble
          case Some(VDouble(d)) => d
          case _ => 1.0
        }

        var accum = 0.0
        scala.util.Try(
          validated match {
            case p: jts.geom.Polygon if !p.isEmpty=>
              polygon.FractionalRasterizer.foreachCellByPolygon(Polygon(p), re)(new FractionCallback{
                def callback(col: Int, row: Int, frac: Double) = {
                  if (col >=0 && col < re.cols && row >= 0 && row < re.rows) {
                    raster.setDouble(col, row, frac * cellArea * levels + (if (raster.getDouble(col, row).isNaN) 0.0 else raster.getDouble(col, row)))
                    accum += frac * cellArea
                  }
                }
              })
            case mp: jts.geom.MultiPolygon =>
              polygon.FractionalRasterizer.foreachCellByMultiPolygon(MultiPolygon(mp), re)(new FractionCallback{
                def callback(col: Int, row: Int, frac: Double) = {
                  if (col >=0 && col < re.cols && row >= 0 && row < re.rows) {
                    raster.setDouble(col, row, frac * cellArea * levels + (if (raster.getDouble(col, row).isNaN) 0.0 else raster.getDouble(col, row)))
                    accum += frac * cellArea
                  }
                }
              })
            case g => println(s"Encountered unexpected geometry: $g\ninitial feature: $poly")
          }
        ) match {
          case scala.util.Failure(ex) =>
            println(tileExtent.toPolygon)
            throw ex
          case _ => ()
        }

      }}

    raster
  }

}
