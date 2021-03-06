import cucumber.api.DataTable
import cucumber.api.scala.{EN, ScalaDsl}
import org.apache.spark.rdd.RDD
import org.scalatest.Matchers
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.collection.convert.wrapAsScala._

class ComplexSparkSteps extends ScalaDsl with EN with Matchers {
  def dataTableToDataFrame(data: DataTable): DataFrame = {
    val fieldSpec = data
      .topCells()
      .map(_.split(':'))
      .map(splits => (splits(0), splits(1).toLowerCase))
      .map {
        case (name, "string") => (name, DataTypes.StringType)
        case (name, "double") => (name, DataTypes.DoubleType)
        case (name, "int") => (name, DataTypes.IntegerType)
        case (name, "integer") => (name, DataTypes.IntegerType)
        case (name, "long") => (name, DataTypes.LongType)
        case (name, "boolean") => (name, DataTypes.BooleanType)
        case (name, "bool") => (name, DataTypes.BooleanType)
        case (name, _) => (name, DataTypes.StringType)
      }

    val schema = StructType(
      fieldSpec
        .map { case (name, dataType) =>
          StructField(name, dataType, nullable = false)
        }
    )

    val rows = data
      .asMaps(classOf[String], classOf[String])
      .map { row =>
        val values = row
          .values()
          .zip(fieldSpec)
          .map { case (v, (fn, dt)) => (v, dt) }
          .map {
            case (v, DataTypes.IntegerType) => v.toInt
            case (v, DataTypes.DoubleType) => v.toDouble
            case (v, DataTypes.LongType) => v.toLong
            case (v, DataTypes.BooleanType) => v.toBoolean
            case (v, DataTypes.StringType) => v
          }
          .toSeq

        Row.fromSeq(values)
      }
      .toList

    val df = Spark.sqlContext.createDataFrame(Spark.sc.parallelize(rows), schema)
    df
  }

  Given("""^a table of data in a temp table called "([^"]*)"$""") { (tableName: String, data: DataTable) =>
    val df = dataTableToDataFrame(data)
    df.registerTempTable(tableName)

    df.printSchema()
    df.show()
  }

  When("""^I join the data$"""){ () =>
    val housePrices = Spark.sqlContext.sql("select * from housePrices")
    val postcodes = Spark.sqlContext.sql("select * from postcodes")

    val result = HousePriceDataBusinessLogic.processHousePrices(housePrices, postcodes)
    result.registerTempTable("results")
  }

  Then("""^the data in temp table "([^"]*)" is$"""){ (tableName: String, expectedData: DataTable) =>
    val expectedDf = dataTableToDataFrame(expectedData)
    val actualDf = Spark.sqlContext.sql(s"select * from $tableName")

    val cols = expectedDf.schema.map(_.name).sorted

    val expected = expectedDf.select(cols.head, cols.tail: _*)
    val actual = actualDf.select(cols.head, cols.tail: _*)

    println("Comparing DFs (expected, actual):")
    expected.show()
    actual.show()

    actual.count() shouldEqual expected.count()
    expected.intersect(actual).count() shouldEqual expected.count()
  }

  class MockParquetWriter extends ParquetWriter {
    override def write(df: DataFrame, path: String): Unit = {
      Context.parquetFilename = path
      Context.savedData = df
    }
  }

  When("""^I join the data and save to parquet$"""){ () =>
    val housePrices = Spark.sqlContext.sql("select * from housePrices")
    val postcodes = Spark.sqlContext.sql("select * from postcodes")

    val result = HousePriceDataBusinessLogic.processHousePricesAndSaveToParquet(housePrices, postcodes, new MockParquetWriter)
  }

  Then("""^the parquet data written to "([^"]*)" is$"""){ (expectedFilename: String, expectedData: DataTable) =>
    val expected = dataTableToDataFrame(expectedData)

    Context.parquetFilename shouldEqual expectedFilename
    Context.savedData.count() shouldEqual expected.count()
    expected.intersect(Context.savedData).count() shouldEqual 0
  }

  Given("""^a file called "([^"]*)" containing$"""){ (filename:String, data:String) =>
    Context.files = Context.files + (filename -> data)
  }

  When("""^I read the data from "([^"]*)" and "([^"]*)" join then save to parquet$"""){ (priceFile:String, postcodeFile:String) =>
    HousePriceDataBusinessLogic.processDataFromFilesAndSaveToParquet(new MockFileReader, priceFile, postcodeFile, new MockParquetWriter)
  }

  When("""^I read the data from "([^"]*)" and "([^"]*)" join it filter it using "([^"]*)" then save to parquet$"""){ (priceFile: String, postcodeFile: String, geoFile : String) =>
    HousePriceDataBusinessLogic.processDataFromFilesFilterItThenSaveItToParquet(new MockFileReader, geoFile, priceFile, postcodeFile, new MockParquetWriter)
  }
}
