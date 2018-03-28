package com.azavea.hotosmpopulation

import geotrellis.vector._
import geotrellis.shapefile._

object CountryGeometry {

  def apply(countryADM3Code: String): Option[MultiPolygonFeature[Map[String, Object]]] = {

    val url = getClass.getResource("/ne_50m_admin_0_countries.shp")
    val allCountries = ShapeFileReader.readMultiPolygonFeatures(url)

    allCountries.filter{country => country.data("ADM0_A3") == countryADM3Code}.headOption
  }

}
