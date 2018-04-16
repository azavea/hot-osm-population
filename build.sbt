name := "hotosmpopulation"

version := "0.0.1"

description := "Estimate OSM coverage from World Population raster"

organization := "com.azavea"

organizationName := "Azavea"

scalaVersion in ThisBuild := Version.scala

val common = Seq(
  resolvers ++= Seq(
    "locationtech-releases" at "https://repo.locationtech.org/content/groups/releases",
    //"locationtech-snapshots" at "https://repo.locationtech.org/content/groups/snapshots",
    Resolver.bintrayRepo("azavea", "maven"),
    Resolver.bintrayRepo("s22s", "maven"),
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
    "io.astraea" %% "raster-frames" % "0.6.2-SNAPSHOT",
    "org.geotools" % "gt-shapefile" % Version.geotools,
    // This is one finicky dependency. Being explicit in hopes it will stop hurting Travis.
    "javax.media" % "jai_core" % "1.1.3" from "http://download.osgeo.org/webdav/geotools/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
    "org.apache.spark"            %% "spark-hive"            % Version.spark % Provided,
    "org.apache.spark"            %% "spark-core"            % Version.spark % Provided,
    "org.apache.spark"            %% "spark-sql"             % Version.spark % Provided,
    "org.apache.spark"            %% "spark-mllib"           % Version.spark % Provided,
    "org.locationtech.geotrellis" %% "geotrellis-proj4"      % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-vector"     % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-raster"     % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-shapefile"  % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-spark"      % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-util"       % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-s3"         % Version.geotrellis,
    "org.locationtech.geotrellis" %% "geotrellis-vectortile" % Version.geotrellis,
    "com.amazonaws"               %  "aws-java-sdk-s3"       % "1.11.143",
    "org.scalatest"               %% "scalatest"             % "3.0.1" % Test,
    "org.spire-math"              %% "spire"                 % Version.spire,
    "org.typelevel"               %% "cats-core"             % "1.0.0-RC1",
    "com.monovore"                %% "decline"               % "0.4.0-RC1",
    "org.tpolecat"                %% "doobie-core"           % "0.5.2",
    "org.xerial"                  %  "sqlite-jdbc"           % "3.21.0"
),

  parallelExecution in Test := false
)

fork in console := true
javaOptions += "-Xmx8G -XX:+UseParallelGC"

val release = Seq(
  licenses += ("Apache-2.0", url("http://apache.org/licenses/LICENSE-2.0"))
)

assemblyJarName in assembly := "hot-osm-population-assembly.jar"

val MetaInfDiscardRx = """^META-INF(.+)\.(SF|RSA|MF)$""".r

assemblyMergeStrategy in assembly := {
  case s if s.startsWith("META-INF/services") => MergeStrategy.concat
  case "reference.conf" | "application.conf"  => MergeStrategy.concat
  case MetaInfDiscardRx(_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

lazy val root = Project("hot-osm-population", file(".")).
  settings(common, release).
  settings(
    initialCommands in console :=
      """
      |import geotrellis.proj4._
      |import geotrellis.raster._
      |import geotrellis.raster.resample._
      |import geotrellis.spark._
      |import geotrellis.spark.tiling._
      |import geotrellis.vector._
      |import com.azavea.hotosmpopulation._
      |import com.azavea.hotosmpopulation.Utils._
      |import astraea.spark.rasterframes._
      |import astraea.spark.rasterframes.ml.TileExploder
      |import org.apache.spark.sql._
      |import org.apache.spark.sql.functions._
      |import org.apache.spark.ml.regression._
      |import org.apache.spark.storage.StorageLevel
      |import geotrellis.spark._
      |
      |implicit val spark: SparkSession = SparkSession.builder().
      |    master("local[8]").appName("RasterFrames").
      |    config("spark.ui.enabled", "true").
      |    config("spark.driver.maxResultSize", "2G").
      |    getOrCreate().
      |    withRasterFrames
      |
      |import spark.implicits._
      """.stripMargin
  )
