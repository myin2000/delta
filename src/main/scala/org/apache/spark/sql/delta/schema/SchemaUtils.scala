/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.schema

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.{Resolver, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.functions.{col, struct}
import org.apache.spark.sql.types._

object SchemaUtils {
  // We use case insensitive resolution while writing into Delta
  val DELTA_COL_RESOLVER = org.apache.spark.sql.catalyst.analysis.caseInsensitiveResolution

  /**
   * Finds `StructField`s that match a given check `f`. Returns the path to the column, and the
   * field.
   *
   * @param checkComplexTypes While `StructType` is also a complex type, since we're returning
   *                          StructFields, we definitely recurse into StructTypes. This flag
   *                          defines whether we should recurse into ArrayType and MapType.
   */
  def filterRecursively(
      schema: StructType,
      checkComplexTypes: Boolean)(f: StructField => Boolean): Seq[(Seq[String], StructField)] = {
    def recurseIntoComplexTypes(
        complexType: DataType,
        columnStack: Seq[String]): Seq[(Seq[String], StructField)] = complexType match {
      case s: StructType =>
        s.fields.flatMap { sf =>
          val includeLevel = if (f(sf)) Seq((columnStack, sf)) else Nil
          includeLevel ++ recurseIntoComplexTypes(sf.dataType, columnStack :+ sf.name)
        }
      case a: ArrayType if checkComplexTypes => recurseIntoComplexTypes(a.elementType, columnStack)
      case m: MapType if checkComplexTypes =>
        recurseIntoComplexTypes(m.keyType, columnStack :+ "key") ++
          recurseIntoComplexTypes(m.valueType, columnStack :+ "value")
      case _ => Nil
    }

    recurseIntoComplexTypes(schema, Nil)
  }

  /** Copied over from DataType for visibility reasons. */
  def typeExistsRecursively(dt: DataType)(f: DataType => Boolean): Boolean = dt match {
    case s: StructType =>
      f(s) || s.fields.exists(field => typeExistsRecursively(field.dataType)(f))
    case a: ArrayType =>
      f(a) || typeExistsRecursively(a.elementType)(f)
    case m: MapType =>
      f(m) || typeExistsRecursively(m.keyType)(f) || typeExistsRecursively(m.valueType)(f)
    case other =>
      f(other)
  }

  /**
   * Drops null types from the DataFrame if they exist. We don't have easy ways of generating types
   * such as MapType and ArrayType, therefore if these types contain NullType in their elements,
   * we will throw an AnalysisException.
   */
  def dropNullTypeColumns(df: DataFrame): DataFrame = {
    val schema = df.schema
    if (!typeExistsRecursively(schema)(_.isInstanceOf[NullType])) return df
    def generateSelectExpr(sf: StructField, nameStack: Seq[String]): Column = sf.dataType match {
      case st: StructType =>
        val nested = st.fields.flatMap { f =>
          if (f.dataType.isInstanceOf[NullType]) {
            None
          } else {
            Some(generateSelectExpr(f, nameStack :+ sf.name))
          }
        }
        struct(nested: _*).alias(sf.name)
      case a: ArrayType if typeExistsRecursively(a)(_.isInstanceOf[NullType]) =>
        val colName = UnresolvedAttribute.apply(nameStack :+ sf.name).name
        throw new AnalysisException(
          s"Found nested NullType in column $colName which is of ArrayType. Delta doesn't " +
            "support writing NullType in complex types.")
      case m: MapType if typeExistsRecursively(m)(_.isInstanceOf[NullType]) =>
        val colName = UnresolvedAttribute.apply(nameStack :+ sf.name).name
        throw new AnalysisException(
          s"Found nested NullType in column $colName which is of MapType. Delta doesn't " +
            "support writing NullType in complex types.")
      case _ =>
        val colName = UnresolvedAttribute.apply(nameStack :+ sf.name).name
        col(colName).alias(sf.name)
    }

    val selectExprs = schema.flatMap { f =>
      if (f.dataType.isInstanceOf[NullType]) None else Some(generateSelectExpr(f, Nil))
    }
    df.select(selectExprs: _*)
  }

  /**
   * Returns all column names in this schema as a flat list. For example, a schema like:
   *   | - a
   *   | | - 1
   *   | | - 2
   *   | - b
   *   | - c
   *   | | - nest
   *   |   | - 3
   *   will get flattened to: "a", "a.1", "a.2", "b", "c", "c.nest", "c.nest.3"
   */
  def explodeNestedFieldNames(schema: StructType): Seq[String] = {
    def explode(schema: StructType): Seq[Seq[String]] = {
      def recurseIntoComplexTypes(complexType: DataType): Seq[Seq[String]] = {
        complexType match {
          case s: StructType => explode(s)
          case a: ArrayType => recurseIntoComplexTypes(a.elementType)
          case m: MapType =>
            recurseIntoComplexTypes(m.keyType).map(Seq("key") ++ _) ++
              recurseIntoComplexTypes(m.valueType).map(Seq("value") ++ _)
          case _ => Nil
        }
      }

      schema.flatMap {
        case StructField(name, s: StructType, _, _) =>
          Seq(Seq(name)) ++ explode(s).map(nested => Seq(name) ++ nested)
        case StructField(name, a: ArrayType, _, _) =>
          Seq(Seq(name)) ++ recurseIntoComplexTypes(a).map(nested => Seq(name) ++ nested)
        case StructField(name, m: MapType, _, _) =>
          Seq(Seq(name)) ++ recurseIntoComplexTypes(m).map(nested => Seq(name) ++ nested)
        case f => Seq(f.name) :: Nil
      }
    }

    explode(schema).map(UnresolvedAttribute.apply(_).name)
  }

  /**
   * Checks if input column names have duplicate identifiers. This throws an exception if
   * the duplication exists.
   *
   * @param schema the schema to check for duplicates
   * @param colType column type name, used in an exception message
   */
  def checkColumnNameDuplication(schema: StructType, colType: String): Unit = {
    val columnNames = explodeNestedFieldNames(schema)
    // scalastyle:off caselocale
    val names = columnNames.map(_.toLowerCase)
    // scalastyle:on caselocale
    if (names.distinct.length != names.length) {
      val duplicateColumns = names.groupBy(identity).collect {
        case (x, ys) if ys.length > 1 => s"$x"
      }
      throw new AnalysisException(
        s"Found duplicate column(s) $colType: ${duplicateColumns.mkString(", ")}")
    }
  }

  /**
   * Rewrite the query field names according to the table schema. This method assumes that all
   * schema validation checks have been made and this is the last operation before writing into
   * Delta.
   */
  def normalizeColumnNames(baseSchema: StructType, data: Dataset[_]): DataFrame = {
    val dataSchema = data.schema
    val dataFields = explodeNestedFieldNames(dataSchema).toSet
    val tableFields = explodeNestedFieldNames(baseSchema).toSet
    if (dataFields.subsetOf(tableFields)) {
      data.toDF()
    } else {
      // Check that nested columns don't need renaming. We can't handle that right now
      val topLevelDataFields = dataFields.map(UnresolvedAttribute.parseAttributeName(_).head)
      if (topLevelDataFields.subsetOf(tableFields)) {
        val columnsThatNeedRenaming = dataFields -- tableFields
        throw new AnalysisException("Nested fields need renaming to avoid data loss. " +
          s"Fields:\n${columnsThatNeedRenaming.mkString("[", ", ", "]")}.\n" +
          s"Original schema:\n${baseSchema.treeString}")
      }

      val baseFields = toFieldMap(baseSchema)
      val aliasExpressions = dataSchema.map { field =>
        val originalCase = baseFields.get(field.name).getOrElse {
          throw new AnalysisException(
            s"Can't resolve column ${field.name} in ${baseSchema.treeString}")
        }
        if (originalCase.name != field.name) {
          functions.col(field.name).as(originalCase.name)
        } else {
          functions.col(field.name)
        }
      }
      data.select(aliasExpressions: _*)
    }
  }

  /**
   * As the Delta snapshots update, the schema may change as well. This method defines whether the
   * new schema of a Delta table can be used with a previously analyzed LogicalPlan. Our
   * rules are to return false if:
   *   - Dropping any column that was present in the DataFrame schema
   *   - Converting nullable=false to nullable=true for any column
   *   - Any change of datatype
   */
  def isReadCompatible(existingSchema: StructType, readSchema: StructType): Boolean = {
    val existing = toFieldMap(existingSchema)
    // scalastyle:off caselocale
    val existingFieldNames = existingSchema.fieldNames.map(_.toLowerCase).toSet
    assert(existingFieldNames.size == existingSchema.length,
      "Delta tables don't allow field names that only differ by case")
    val newFields = readSchema.fieldNames.map(_.toLowerCase).toSet
    assert(newFields.size == readSchema.length,
      "Delta tables don't allow field names that only differ by case")
    // scalastyle:on caselocale

    if (!existingFieldNames.subsetOf(newFields)) {
      // Dropped a column that was present in the DataFrame schema
      return false
    }

    readSchema.forall { newField =>
      existing.get(newField.name) match {
        case Some(existingField) =>
          val nullabilityConstraintMet = if (!existingField.nullable) {
            // newField should also be non-nullable
            !newField.nullable
          } else {
            true
          }
          val dataTypeMatched = (existingField.dataType, newField.dataType) match {
            case (e: StructType, n: StructType) =>
              isReadCompatible(e, n)
            case (ArrayType(e: StructType, _), ArrayType(n: StructType, _)) =>
              isReadCompatible(e, n)
            case (a, b) => a == b
          }
          dataTypeMatched && nullabilityConstraintMet
        case None =>
          // new fields are fine, they just won't be returned
          true
      }
    }
  }

  /**
   * Returns the given column's ordinal within the given `schema` and the size of the last schema
   * size. The length of the returned position will be as long as how nested the column is.
   *
   * @param column The column to search for in the given struct. If the length of `column` is
   *               greater than 1, we expect to enter a nested field.
   * @param schema The current struct we are looking at.
   * @param resolver The resolver to find the column.
   */
  def findColumnPosition(
      column: Seq[String],
      schema: StructType,
      resolver: Resolver = DELTA_COL_RESOLVER): (Seq[Int], Int) = {
    def find(column: Seq[String], schema: StructType, stack: Seq[String]): (Seq[Int], Int) = {
      if (column.isEmpty) return (Nil, schema.size)
      val thisCol = column.head
      lazy val columnPath = UnresolvedAttribute(stack :+ thisCol).name
      val pos = schema.indexWhere(f => resolver(f.name, thisCol))
      if (pos == -1) {
        throw new IndexOutOfBoundsException(columnPath)
      }
      val (children, lastSize) = schema(pos).dataType match {
        case s: StructType =>
          find(column.tail, s, stack :+ thisCol)
        case ArrayType(s: StructType, _) =>
          find(column.tail, s, stack :+ thisCol)
        case o =>
          if (column.length > 1) {
            throw new AnalysisException(
              s"""Expected $columnPath to be a nested data type, but found $o. Was looking for the
                 |index of ${UnresolvedAttribute(column).name} in a nested field
              """.stripMargin)
          }
          (Nil, 0)
      }
      (Seq(pos) ++ children, lastSize)
    }

    try {
      find(column, schema, Nil)
    } catch {
      case i: IndexOutOfBoundsException =>
        throw new AnalysisException(
          s"Couldn't find column ${i.getMessage} in:\n${schema.treeString}")
      case e: AnalysisException =>
        throw new AnalysisException(e.getMessage + s":\n${schema.treeString}")
    }
  }

  /**
   * Add `column` to the specified `position` in `schema`.
   * @param position A Seq of ordinals on where this column should go. It is a Seq to denote
   *                 positions in nested columns (0-based). For example:
   *
   *                 tableSchema: <a:STRUCT<a1,a2,a3>, b,c:STRUCT<c1,c3>>
   *                 column: c2
   *                 position: Seq(2, 1)
   *                 will return
   *                 result: <a:STRUCT<a1,a2,a3>, b,c:STRUCT<c1,**c2**,c3>>
   */
  def addColumn(schema: StructType, column: StructField, position: Seq[Int]): StructType = {
    require(position.nonEmpty, s"Don't know where to add the column $column")
    val slicePosition = position.head
    if (slicePosition < 0) {
      throw new AnalysisException(s"Index $slicePosition to add column $column is lower than 0")
    }
    val length = schema.length
    if (slicePosition > length) {
      throw new AnalysisException(
        s"Index $slicePosition to add column $column is larger than struct length: $length")
    }
    if (slicePosition == length) {
      if (position.length > 1) {
        throw new AnalysisException(s"Struct not found at position $slicePosition")
      }
      return StructType(schema :+ column)
    }
    val pre = schema.take(slicePosition)
    if (position.length > 1) {
      val mid = schema(slicePosition) match {
        case StructField(name, f: StructType, nullable, metadata) =>
          if (!column.nullable && nullable) {
            throw new AnalysisException(
              "A non-nullable nested field can't be added to a nullable parent. Please set the " +
              "nullability of the parent column accordingly.")
          }
          StructField(
            name,
            addColumn(f, column, position.tail),
            nullable,
            metadata)
        case o =>
          throw new AnalysisException(s"Can only add nested columns to StructType. Found: $o")
      }
      StructType(pre ++ Seq(mid) ++ schema.slice(slicePosition + 1, length))
    } else {
      StructType(pre ++ Seq(column) ++ schema.slice(slicePosition, length))
    }
  }

  /**
   * Drop from the specified `position` in `schema` and return with the original column.
   * @param position A Seq of ordinals on where this column should go. It is a Seq to denote
   *                 positions in nested columns (0-based). For example:
   *
   *                 tableSchema: <a:STRUCT<a1,a2,a3>, b,c:STRUCT<c1,c2,c3>>
   *                 position: Seq(2, 1)
   *                 will return
   *                 result: <a:STRUCT<a1,a2,a3>, b,c:STRUCT<c1,c3>>
   */
  def dropColumn(schema: StructType, position: Seq[Int]): (StructType, StructField) = {
    require(position.nonEmpty, "Don't know where to drop the column")
    val slicePosition = position.head
    if (slicePosition < 0) {
      throw new AnalysisException(s"Index $slicePosition to drop column is lower than 0")
    }
    val length = schema.length
    if (slicePosition >= length) {
      throw new AnalysisException(
        s"Index $slicePosition to drop column equals to or is larger than struct length: $length")
    }
    val pre = schema.take(slicePosition)
    if (position.length > 1) {
      val (mid, original) = schema(slicePosition) match {
        case StructField(name, f: StructType, nullable, metadata) =>
          val (dropped, original) = dropColumn(f, position.tail)
          (StructField(name, dropped, nullable, metadata), original)
        case o =>
          throw new AnalysisException(s"Can only drop nested columns from StructType. Found: $o")
      }
      (StructType(pre ++ Seq(mid) ++ schema.slice(slicePosition + 1, length)), original)
    } else {
      (StructType(pre ++ schema.slice(slicePosition + 1, length)), schema(slicePosition))
    }
  }

  /**
   * Check if the two data types can be changed.
   *
   * @return None if the data types can be changed, otherwise Some(err) containing the reason.
   */
  def canChangeDataType(
      from: DataType,
      to: DataType,
      resolver: Resolver,
      columnPath: Seq[String] = Seq.empty): Option[String] = {
    def verify(cond: Boolean, err: => String): Unit = {
      if (!cond) {
        throw new AnalysisException(err)
      }
    }

    def verifyNullability(fn: Boolean, tn: Boolean, columnPath: Seq[String]): Unit = {
      verify(tn || !fn, s"tightening nullability of ${UnresolvedAttribute(columnPath).name}")
    }

    def check(fromDt: DataType, toDt: DataType, columnPath: Seq[String]): Unit = {
      (fromDt, toDt) match {
        case (ArrayType(fromElement, fn), ArrayType(toElement, tn)) =>
          verifyNullability(fn, tn, columnPath)
          check(fromElement, toElement, columnPath)

        case (MapType(fromKey, fromValue, fn), MapType(toKey, toValue, tn)) =>
          verifyNullability(fn, tn, columnPath)
          check(fromKey, toKey, columnPath :+ "key")
          check(fromValue, toValue, columnPath :+ "value")

        case (StructType(fromFields), StructType(toFields)) =>
          val remainingFields = fromFields.to[mutable.Set]
          toFields.foreach { toField =>
            fromFields.find(field => resolver(field.name, toField.name)) match {
              case Some(fromField) =>
                remainingFields -= fromField

                val newPath = columnPath :+ fromField.name
                verifyNullability(fromField.nullable, toField.nullable, newPath)
                check(fromField.dataType, toField.dataType, newPath)
              case None =>
                verify(toField.nullable,
                  "adding non-nullable column " +
                  UnresolvedAttribute(columnPath :+ toField.name).name)
            }
          }
          verify(remainingFields.isEmpty,
            s"dropping column(s) [${remainingFields.map(_.name).mkString(", ")}]" +
            (if (columnPath.nonEmpty) s" from ${UnresolvedAttribute(columnPath).name}" else ""))

        case (fromDataType, toDataType) =>
          verify(fromDataType == toDataType,
            s"changing data type of ${UnresolvedAttribute(columnPath).name} " +
            s"from $fromDataType to $toDataType")
      }
    }

    try {
      check(from, to, columnPath)
      None
    } catch {
      case e: AnalysisException =>
        Some(e.message)
    }
  }

  /**
   * Copy the nested data type between two data types.
   */
  def changeDataType(from: DataType, to: DataType, resolver: Resolver): DataType = {
    (from, to) match {
      case (ArrayType(fromElement, fn), ArrayType(toElement, tn)) =>
        ArrayType(changeDataType(fromElement, toElement, resolver), fn)

      case (MapType(fromKey, fromValue, fn), MapType(toKey, toValue, tn)) =>
        MapType(
          changeDataType(fromKey, toKey, resolver),
          changeDataType(fromValue, toValue, resolver),
          fn)

      case (StructType(fromFields), StructType(toFields)) =>
        StructType(
          toFields.map { toField =>
            fromFields.find(field => resolver(field.name, toField.name)).map { fromField =>
              toField.getComment.map(fromField.withComment).getOrElse(fromField)
                .copy(
                  dataType = changeDataType(fromField.dataType, toField.dataType, resolver),
                  nullable = toField.nullable)
            }.getOrElse(toField)
          }
        )

      case (_, toDataType) => toDataType
    }
  }

  /**
   * Check whether we can write to the Delta table, which has `tableSchema`, using a query that has
   * `dataSchema`. Our rules are that:
   *   - `dataSchema` may be missing columns or have additional columns
   *   - We don't trust the nullability in `dataSchema`. Assume fields are nullable.
   *   - We only allow nested StructType expansions. For all other complex types, we check for
   *     strict equality
   *   - `dataSchema` can't have duplicate column names. Columns that only differ by case are also
   *     not allowed.
   * The following merging strategy is
   * applied:
   *  - The name of the current field is used.
   *  - The data types are merged by calling this function.
   *  - We respect the current field's nullability.
   *  - The metadata is current field's metadata.
   *
   * Schema merging occurs in a case insensitive manner. Hence, column names that only differ
   * by case are not accepted in the `dataSchema`.
   */
  def mergeSchemas(tableSchema: StructType, dataSchema: StructType): StructType = {
    checkColumnNameDuplication(dataSchema, "in the data to save")
    def merge(current: DataType, update: DataType): DataType = {
      (current, update) match {
        case (StructType(currentFields), StructType(updateFields)) =>
          // Merge existing fields.
          val updateFieldMap = toFieldMap(updateFields)
          val updatedCurrentFields = currentFields.map { currentField =>
            updateFieldMap.get(currentField.name) match {
              case Some(updateField) =>
                try {
                  StructField(
                    currentField.name,
                    merge(currentField.dataType, updateField.dataType),
                    currentField.nullable,
                    currentField.metadata)
                } catch {
                  case NonFatal(e) =>
                    throw new AnalysisException(s"Failed to merge fields '${currentField.name}' " +
                        s"and '${updateField.name}'. " + e.getMessage)
                }
              case None =>
                // Retain the old field.
                currentField
            }
          }

          // Identify the newly added fields.
          val nameToFieldMap = toFieldMap(currentFields)
          val newFields = updateFields.filterNot(f => nameToFieldMap.contains(f.name))

          // Create the merged struct, the new fields are appended at the end of the struct.
          StructType(updatedCurrentFields ++ newFields)
        case (ArrayType(currentElementType, currentContainsNull),
              ArrayType(updateElementType, _)) =>
          ArrayType(
            merge(currentElementType, updateElementType),
            currentContainsNull)
        case (MapType(currentKeyType, currentElementType, currentContainsNull),
              MapType(updateKeyType, updateElementType, _)) =>
          MapType(
            merge(currentKeyType, updateKeyType),
            merge(currentElementType, updateElementType),
            currentContainsNull)
        case (DecimalType.Fixed(leftPrecision, leftScale),
              DecimalType.Fixed(rightPrecision, rightScale)) =>
          if ((leftPrecision == rightPrecision) && (leftScale == rightScale)) {
            current
          } else if ((leftPrecision != rightPrecision) && (leftScale != rightScale)) {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"precision $leftPrecision and $rightPrecision & scale $leftScale and $rightScale")
          } else if (leftPrecision != rightPrecision) {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"precision $leftPrecision and $rightPrecision")
          } else {
            throw new AnalysisException("Failed to merge decimal types with incompatible " +
              s"scale $leftScale and $rightScale")
          }
        case _ if current == update =>
          current

        // Parquet physically stores ByteType, ShortType and IntType as IntType, so when a parquet
        // column is of one of these three types, you can read this column as any of these three
        // types. Since Parquet doesn't complain, we should also allow upcasting among these
        // three types when merging schemas.
        case (ByteType, ShortType) => ShortType
        case (ByteType, IntegerType) => IntegerType

        case (ShortType, ByteType) => ShortType
        case (ShortType, IntegerType) => IntegerType

        case (IntegerType, ShortType) => IntegerType
        case (IntegerType, ByteType) => IntegerType

        case (NullType, _) =>
          update
        case (_, NullType) =>
          current
        case _ =>
          throw new AnalysisException(
            s"Failed to merge incompatible data types $current and $update")
      }
    }
    merge(tableSchema, dataSchema).asInstanceOf[StructType]
  }

  private def toFieldMap(fields: Seq[StructField]): Map[String, StructField] = {
    CaseInsensitiveMap(fields.map(field => field.name -> field).toMap)
  }

  /**
   * Transform (nested) columns in a schema.
   *
   * @param schema to transform.
   * @param tf function to apply.
   * @return the transformed schema.
   */
  def transformColumns(
      schema: StructType)(
      tf: (Seq[String], StructField, Resolver) => StructField): StructType = {
    def transform[E <: DataType](path: Seq[String], dt: E): E = {
      val newDt = dt match {
        case StructType(fields) =>
          StructType(fields.map { field =>
            val newField = tf(path, field, DELTA_COL_RESOLVER)
            newField.copy(dataType = transform(path :+ newField.name, newField.dataType))
          })
        case ArrayType(elementType, containsNull) =>
          ArrayType(transform(path, elementType), containsNull)
        case MapType(keyType, valueType, valueContainsNull) =>
          MapType(
            transform(path :+ "key", keyType),
            transform(path :+ "value", valueType),
            valueContainsNull)
        case other => other
      }
      newDt.asInstanceOf[E]
    }
    transform(Seq.empty, schema)
  }

  /**
   * Transform (nested) columns in a schema using the given path and parameter pairs. The transform
   * function is only invoked when a field's path matches one of the input paths.
   *
   * @param schema to transform
   * @param input paths and parameter pairs. The paths point to fields we want to transform. The
   *              parameters will be passed to the transform function for a matching field.
   * @param tf function to apply per matched field. This function takes the field path, the field
   *           itself and the input names and payload pairs that matched the field name. It should
   *           return a new field.
   * @tparam E the type of the payload used for transforming fields.
   * @return the transformed schema.
   */
  def transformColumns[E](
      schema: StructType,
      input: Seq[(Seq[String], E)])(
      tf: (Seq[String], StructField, Seq[(Seq[String], E)]) => StructField): StructType = {
    // scalastyle:off caselocale
    val inputLookup = input.groupBy(_._1.map(_.toLowerCase))
    SchemaUtils.transformColumns(schema) { (path, field, resolver) =>
      // Find the parameters that match this field name.
      val fullPath = path :+ field.name
      val normalizedFullPath = fullPath.map(_.toLowerCase)
      val matches = inputLookup.get(normalizedFullPath).toSeq.flatMap {
        // Keep only the input name(s) that actually match the field name(s). Note
        // that the Map guarantees that the zipped sequences have the same size.
        _.filter(_._1.zip(fullPath).forall(resolver.tupled))
      }
      if (matches.nonEmpty) {
        tf(path, field, matches)
      } else {
        field
      }
    }
    // scalastyle:on caselocale
  }
}
