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

package org.apache.spark.sql.hive.execution

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.ql.metadata.{Partition => HivePartition}
import org.apache.hadoop.hive.ql.plan.TableDesc
import org.apache.hadoop.hive.serde.serdeConstants
import org.apache.hadoop.hive.serde2.objectinspector._
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.CastSupport
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.hive._
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BooleanType, DataType}
import org.apache.spark.util.Utils

/**
 * The Hive table scan operator.  Column and partition pruning are both handled.
 *
 * @param requestedAttributes Attributes to be fetched from the Hive table.
 * @param relation The Hive table be scanned.
 * @param partitionPruningPred An optional partition pruning predicate for partitioned table.
 */
private[hive]
case class HiveTableScanExec(  // 贴源的, 但他也会实现 doExecute()方法: 181 行
    requestedAttributes: Seq[Attribute],
    relation: HiveTableRelation,
    partitionPruningPred: Seq[Expression])(
    @transient private val sparkSession: SparkSession)
  extends LeafExecNode with CastSupport {

  require(partitionPruningPred.isEmpty || relation.isPartitioned,
    "Partition pruning predicates only supported for partitioned tables.")

  override def conf: SQLConf = sparkSession.sessionState.conf

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"))

  override def producedAttributes: AttributeSet = outputSet ++
    AttributeSet(partitionPruningPred.flatMap(_.references))

  private val originalAttributes = AttributeMap(relation.output.map(a => a -> a))

  override val output: Seq[Attribute] = {
    // Retrieve the original attributes based on expression ID so that capitalization matches.
    requestedAttributes.map(originalAttributes)
  }

  // Bind all partition key attribute references in the partition pruning predicate for later
  // evaluation.
  private lazy val boundPruningPred = partitionPruningPred.reduceLeftOption(And).map { pred =>
    require(
      pred.dataType == BooleanType,
      s"Data type of predicate $pred must be BooleanType rather than ${pred.dataType}.")

    BindReferences.bindReference(pred, relation.partitionCols)
  }

  @transient private lazy val hiveQlTable = HiveClientImpl.toHiveTable(relation.tableMeta)
  @transient private lazy val tableDesc = new TableDesc(
    hiveQlTable.getInputFormatClass,
    hiveQlTable.getOutputFormatClass,
    hiveQlTable.getMetadata)

  // Create a local copy of hadoopConf,so that scan specific modifications should not impact
  // other queries
  @transient private lazy val hadoopConf = {
    val c = sparkSession.sessionState.newHadoopConf()
    // append columns ids and names before broadcast
    addColumnMetadataToConf(c)
    c
  }

  @transient private lazy val hadoopReader = new HadoopTableReader(
    output,
    relation.partitionCols,
    tableDesc,
    sparkSession,
    hadoopConf)

  private def castFromString(value: String, dataType: DataType) = {
    cast(Literal(value), dataType).eval(null)
  }

  private def addColumnMetadataToConf(hiveConf: Configuration): Unit = {
    // Specifies needed column IDs for those non-partitioning columns.
    val columnOrdinals = AttributeMap(relation.dataCols.zipWithIndex)
    val neededColumnIDs = output.flatMap(columnOrdinals.get).map(o => o: Integer)

    HiveShim.appendReadColumns(hiveConf, neededColumnIDs, output.map(_.name))

    val deserializer = tableDesc.getDeserializerClass.newInstance
    deserializer.initialize(hiveConf, tableDesc.getProperties)

    // Specifies types and object inspectors of columns to be scanned.
    val structOI = ObjectInspectorUtils
      .getStandardObjectInspector(
        deserializer.getObjectInspector,
        ObjectInspectorCopyOption.JAVA)
      .asInstanceOf[StructObjectInspector]

    val columnTypeNames = structOI
      .getAllStructFieldRefs.asScala
      .map(_.getFieldObjectInspector)
      .map(TypeInfoUtils.getTypeInfoFromObjectInspector(_).getTypeName)
      .mkString(",")

    hiveConf.set(serdeConstants.LIST_COLUMN_TYPES, columnTypeNames)
    hiveConf.set(serdeConstants.LIST_COLUMNS, relation.dataCols.map(_.name).mkString(","))
  }

  /**
   * Prunes partitions not involve the query plan.
   *
   * @param partitions All partitions of the relation.
   * @return Partitions that are involved in the query plan.
   */
  private[hive] def prunePartitions(partitions: Seq[HivePartition]) = {
    boundPruningPred match {
      case None => partitions
      case Some(shouldKeep) => partitions.filter { part =>
        val dataTypes = relation.partitionCols.map(_.dataType)
        val castedValues = part.getValues.asScala.zip(dataTypes)
          .map { case (value, dataType) => castFromString(value, dataType) }

        // Only partitioned values are needed here, since the predicate has already been bound to
        // partition key attribute references.
        val row = InternalRow.fromSeq(castedValues)
        shouldKeep.eval(row).asInstanceOf[Boolean]
      }
    }
  }

  // exposed for tests
  @transient lazy val rawPartitions = {
    val prunedPartitions =
      if (sparkSession.sessionState.conf.metastorePartitionPruning &&
          partitionPruningPred.size > 0) {
        // Retrieve the original attributes based on expression ID so that capitalization matches.
        val normalizedFilters = partitionPruningPred.map(_.transform {
          case a: AttributeReference => originalAttributes(a)
        })
        sparkSession.sessionState.catalog.listPartitionsByFilter(
          relation.tableMeta.identifier,
          normalizedFilters)
      } else {
        sparkSession.sessionState.catalog.listPartitions(relation.tableMeta.identifier)
      }
    prunedPartitions.map(HiveClientImpl.toHivePartition(_, hiveQlTable))
  }

  protected override def doExecute(): RDD[InternalRow] = {  // 贴源了就不会再调用child 子查询的 execute()方法了
    // Using dummyCallSite, as getCallSite can turn out to be expensive with
    // with multiple partitions.
    val rdd = if (!relation.isPartitioned) {
      Utils.withDummyCallSite(sqlContext.sparkContext) {
        hadoopReader.makeRDDForTable(hiveQlTable)
      }
    } else {
      Utils.withDummyCallSite(sqlContext.sparkContext) {
        hadoopReader.makeRDDForPartitionedTable(prunePartitions(rawPartitions))
      }
    }
    val numOutputRows = longMetric("numOutputRows")
    // Avoid to serialize MetastoreRelation because schema is lazy. (see SPARK-15649)
    val outputSchema = schema
    rdd.mapPartitionsWithIndexInternal { (index, iter) =>
      val proj = UnsafeProjection.create(outputSchema)
      proj.initialize(index)
      iter.map { r =>
        numOutputRows += 1
        proj(r)
      }
    }
  }

  override def doCanonicalize(): HiveTableScanExec = {
    val input: AttributeSeq = relation.output
    HiveTableScanExec(
      requestedAttributes.map(QueryPlan.normalizeExprId(_, input)),
      relation.canonicalized.asInstanceOf[HiveTableRelation],
      QueryPlan.normalizePredicates(partitionPruningPred, input))(sparkSession)
  }

  override def otherCopyArgs: Seq[AnyRef] = Seq(sparkSession)
}
