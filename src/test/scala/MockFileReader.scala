import org.apache.spark.rdd.RDD

class MockFileReader extends FileReader{
  override def readLinesToRdd(filename: String): RDD[String] = Spark.sc.parallelize(Context.files(filename).split('\n'))
  override def readText(filename: String): String = Context.files(filename)
}
