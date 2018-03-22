import scala.util.Properties

object Environment {
  def either(environmentVariable: String, default: String): String =
    Properties.envOrElse(environmentVariable, default)

  lazy val hadoopVersion  = either("SPARK_HADOOP_VERSION", "2.8.0")
  lazy val sparkVersion   = either("SPARK_VERSION", "2.2.0")
}
