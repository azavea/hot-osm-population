name := "hotosmpopulation"

version := "0.0.1"

description := "Estimate OSM coverage from World Population raster"

organization := "com.azavea"

organizationName := "Azavea"

scalaVersion in ThisBuild := Version.scala

val common = Seq(
  resolvers ++= Seq(
    "locationtech-releases" at "https://repo.locationtech.org/content/groups/releases",
    Resolver.bintrayRepo("azavea", "maven"),
    "Geotools" at "http://download.osgeo.org/webdav/geotools/"
  ),

  scalacOptions := Seq(
    "-deprecation",
    "-Ypartial-unification",
    "-Ywarn-value-discard",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen"
  ),

  scalacOptions in (Compile, doc) += "-groups",

  libraryDependencies ++= Seq(
    "org.geotools" % "gt-shapefile" % Version.geotools,
    // This is one finicky dependency. Being explicit in hopes it will stop hurting Travis.
    "javax.media" % "jai_core" % "1.1.3" from "http://download.osgeo.org/webdav/geotools/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
    "org.apache.spark"            %% "spark-hive"            % Version.spark % "provided",
    "org.apache.spark"            %% "spark-core"            % Version.spark % "provided",
    "org.locationtech.geotrellis" %% "geotrellis-proj4"      % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-vector"     % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-raster"     % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-shapefile"  % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-spark"      % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-util"       % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-s3"         % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-vectortile" % Version.geotrellis,
    "com.amazonaws"               %  "aws-java-sdk-s3"       % "1.11.143",
    "org.scalatest"               %% "scalatest"             % "3.0.1" % "test",
    "org.spire-math"              %% "spire"                 % Version.spire,
    "org.typelevel"               %% "cats-core"             % "1.0.0-RC1"
  ),

  parallelExecution in Test := false
)

val release = Seq(
  licenses += ("Apache-2.0", url("http://apache.org/licenses/LICENSE-2.0"))
)

assemblyMergeStrategy in assembly := {
  case s if s.startsWith("META-INF/services") => MergeStrategy.concat
  case "reference.conf" | "application.conf"  => MergeStrategy.concat
  case "META-INF/MANIFEST.MF" | "META-INF\\MANIFEST.MF" => MergeStrategy.discard
  case "META-INF/ECLIPSEF.RSA" | "META-INF/ECLIPSEF.SF" => MergeStrategy.discard
  case _ => MergeStrategy.first
}

lazy val root = Project("hot-osm-population", file(".")).
  settings(common, release).
  settings(
    initialCommands in console :=
      """
      import geotrellis.proj4._
      import geotrellis.raster._
      import geotrellis.spark._
      import geotrellis.spark.tiling._
      import geotrellis.vector._
      import com.azavea.hotosmpopulation._
      """
  )
