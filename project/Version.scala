object Version {
  val geotrellis  = "2.0.0-SNAPSHOT"
  val scala       = "2.11.12"
  val geotools    = "17.1"
  val spire       = "0.13.0"
  lazy val hadoop = Environment.hadoopVersion
  lazy val spark  = Environment.sparkVersion
}
