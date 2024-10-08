/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources

import java.util.Locale

import scala.collection.mutable

import org.apache.spark.sql.{SparkSession, SQLContext}
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.execution.FileRelation
import org.apache.spark.sql.sources.{BaseRelation, DataSourceRegister}
import org.apache.spark.sql.types.{StructField, StructType}


/**
 * Acts as a container for all of the metadata required to read from a datasource. All discovery,
 * resolution and merging logic for schemas and partitions has been removed.
 *
 * @param location A [[FileIndex]] that can enumerate the locations of all the files that
 *                 comprise this relation.
 * @param partitionSchema The schema of the columns (if any) that are used to partition the relation
 * @param dataSchema The schema of any remaining columns.  Note that if any partition columns are
 *                   present in the actual data files as well, they are preserved.
 * @param bucketSpec Describes the bucketing (hash-partitioning of the files by some column values).
 * @param fileFormat A file format that can be used to read and write the data in files.
 * @param options Configuration used when reading / writing data.
 */
case class HadoopFsRelation( // Spark的SQL最终核实落实到了RDD，后者又落到了Hadoop的文件上。最终还是IO是瓶颈。这个样例类可以看成对于数据源的数据的一个包装
    location: FileIndex,
    partitionSchema: StructType,
    dataSchema: StructType,
    bucketSpec: Option[BucketSpec],
    fileFormat: FileFormat,
    options: Map[String, String])(val sparkSession: SparkSession)
  extends BaseRelation with FileRelation {

  override def sqlContext: SQLContext = sparkSession.sqlContext

  private def getColName(f: StructField): String = {
    if (sparkSession.sessionState.conf.caseSensitiveAnalysis) {
      f.name
    } else {
      f.name.toLowerCase(Locale.ROOT)
    }
  }

  val overlappedPartCols = mutable.Map.empty[String, StructField]
  partitionSchema.foreach { partitionField =>
    if (dataSchema.exists(getColName(_) == getColName(partitionField))) {
      overlappedPartCols += getColName(partitionField) -> partitionField
    }
  }

  // When data and partition schemas have overlapping columns, the output
  // schema respects the order of the data schema for the overlapping columns, and it
  // respects the data types of the partition schema.
  val schema: StructType = {
    StructType(dataSchema.map(f => overlappedPartCols.getOrElse(getColName(f), f)) ++
      partitionSchema.filterNot(f => overlappedPartCols.contains(getColName(f))))
  }

  def partitionSchemaOption: Option[StructType] =
    if (partitionSchema.isEmpty) None else Some(partitionSchema)

  override def toString: String = {
    fileFormat match {
      case source: DataSourceRegister => source.shortName()
      case _ => "HadoopFiles"
    }
  }

  override def sizeInBytes: Long = {
    val compressionFactor = sqlContext.conf.fileCompressionFactor
    (location.sizeInBytes * compressionFactor).toLong
  }


  override def inputFiles: Array[String] = location.inputFiles
}
