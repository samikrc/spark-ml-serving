package io.hydrosphere.spark_ml_serving

import java.io.File

import io.hydrosphere.spark_ml_serving.reader._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import parquet.format.converter.ParquetMetadataConverter.NO_FILTER
import parquet.hadoop.{ParquetFileReader, ParquetReader}
import parquet.schema.MessageType

import scala.collection.immutable.HashMap
import scala.collection.mutable

object ModelDataReader {

  def parse(source: ModelSource, path: String): Map[String, Any] = {
    source.findFile(path, true, _.endsWith(".parquet")) match {
      case Some(p) => readData(p)
      case None => Map.empty
    }
  }

  private def readData(p: Path): Map[String, Any] = {
    val conf: Configuration = new Configuration()
    val metaData = ParquetFileReader.readFooter(conf, p, NO_FILTER)
    val schema: MessageType = metaData.getFileMetaData.getSchema

    val reader = ParquetReader.builder[SimpleRecord](new SimpleReadSupport(), p.getParent).build()
    val result = mutable.HashMap.empty[String, Any]


    try {
      var value = reader.read()
      while (value != null) {
        val valMap = value.struct(HashMap.empty[String, Any], schema)
        mergeMaps(result, valMap)
        value = reader.read()
      }
      result.toMap
    } finally {
      if (reader != null) {
        reader.close()
      }
    }
  }

  // TODO ugly
  private def mergeMaps(acc: mutable.HashMap[String, Any], map: HashMap[String, Any]): Unit = {
    if (map.contains("leftChild") && map.contains("rightChild") && map.contains("id")) { // tree structure detected
      acc += map("id").toString -> map
    } else if (map.contains("treeID") && map.contains("nodeData")) { // ensemble structure detected
      if (!acc.contains(map("treeID").toString)) {
        acc += map("treeID").toString -> mutable.Map.empty[String, Map[String, Any]]
      }
      val nodes = acc(map("treeID").toString)
        .asInstanceOf[mutable.Map[String, Map[String, Any]]]
      val nodeData = map("nodeData").asInstanceOf[Map[String, Any]]
      nodes += nodeData("id").toString -> nodeData
    } else if (map.contains("treeID") && map.contains("metadata")) { // ensemble metadata structure detected
      acc += map("treeID").toString -> map
    } else if (map.contains("clusterIdx") && map.contains("clusterCenter")) { // clusters detected
      acc += map("clusterIdx").toString -> map("clusterCenter")
    } else {
      acc ++= map
    }
  }
}