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

import geotrellis.vector._
import geotrellis.shapefile._

object CountryGeometry {
  def slug(name: String): String = {
    name.toLowerCase.replace(' ', '_')
  }

  def apply(nameOrCode: String): Option[MultiPolygonFeature[Map[String, Object]]] = {
    val url = getClass.getResource("/ne_50m_admin_0_countries.shp")
    val allCountries = ShapeFileReader.readMultiPolygonFeatures(url)

    allCountries.filter{country =>
      val longName: String = country.data("NAME_LONG").asInstanceOf[String]
      val code = country.data("ADM0_A3")
      (code == nameOrCode) || (slug(longName) == slug(nameOrCode))
    }.headOption
  }

}
