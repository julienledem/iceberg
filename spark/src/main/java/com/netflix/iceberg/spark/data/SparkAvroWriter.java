/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.spark.data;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.netflix.iceberg.avro.AvroSchemaUtil;
import com.netflix.iceberg.avro.AvroSchemaVisitor;
import com.netflix.iceberg.types.Type;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.DataType;
import java.io.IOException;
import java.util.List;

import static com.netflix.iceberg.avro.AvroSchemaVisitor.visit;
import static com.netflix.iceberg.spark.SparkSchemaUtil.convert;

public class SparkAvroWriter implements DatumWriter<InternalRow> {
  private final com.netflix.iceberg.Schema schema;
  private ValueWriter<InternalRow> writer = null;

  public SparkAvroWriter(com.netflix.iceberg.Schema schema) {
    this.schema = schema;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setSchema(Schema schema) {
    this.writer = (ValueWriter<InternalRow>) visit(schema, new WriteBuilder(this.schema));
  }

  @Override
  public void write(InternalRow datum, Encoder out) throws IOException {
    writer.write(datum, out);
  }

  private static class WriteBuilder extends AvroSchemaVisitor<ValueWriter<?>> {
    private final com.netflix.iceberg.Schema schema;

    private WriteBuilder(com.netflix.iceberg.Schema schema) {
      this.schema = schema;
    }

    @Override
    public ValueWriter<?> record(Schema record, List<String> names, List<ValueWriter<?>> fields) {
      List<DataType> types = Lists.newArrayList();
      for (Schema.Field field : record.getFields()) {
        types.add(convert(schema.findType(AvroSchemaUtil.getFieldId(field))));
      }
      return ValueWriters.struct(fields, types);
    }

    @Override
    public ValueWriter<?> union(Schema union, List<ValueWriter<?>> options) {
      Preconditions.checkArgument(options.contains(ValueWriters.nulls()),
          "Cannot create writer for non-option union: " + union);
      Preconditions.checkArgument(options.size() == 2,
          "Cannot create writer for non-option union: " + union);
      if (union.getTypes().get(0).getType() == Schema.Type.NULL) {
        return ValueWriters.option(0, options.get(1));
      } else {
        return ValueWriters.option(1, options.get(0));
      }
    }

    @Override
    public ValueWriter<?> array(Schema array, ValueWriter<?> elementReader) {
      Type elementType = schema.findType(AvroSchemaUtil.getElementId(array));
      return ValueWriters.array(elementReader, convert(elementType));
    }

    @Override
    public ValueWriter<?> map(Schema map, ValueWriter<?> valueReader) {
      Type keyType = schema.findType(AvroSchemaUtil.getKeyId(map));
      Type valueType = schema.findType(AvroSchemaUtil.getValueId(map));
      return ValueWriters.map(
          ValueWriters.strings(), convert(keyType), valueReader, convert(valueType));
    }

    @Override
    public ValueWriter<?> primitive(Schema primitive) {
      LogicalType logicalType = primitive.getLogicalType();
      if (logicalType != null) {
        switch (logicalType.getName()) {
          case "date":
            // Spark uses the same representation
            return ValueWriters.ints();

          case "timestamp-micros":
            // Spark uses the same representation
            return ValueWriters.longs();

          case "decimal":
            LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) logicalType;
            return ValueWriters.decimal(decimal.getPrecision(), decimal.getScale());

          case "uuid":
            return ValueWriters.uuids();

          default:
            throw new IllegalArgumentException("Unsupported logical type: " + logicalType);
        }
      }

      switch (primitive.getType()) {
        case NULL:
          return ValueWriters.nulls();
        case BOOLEAN:
          return ValueWriters.booleans();
        case INT:
          return ValueWriters.ints();
        case LONG:
          return ValueWriters.longs();
        case FLOAT:
          return ValueWriters.floats();
        case DOUBLE:
          return ValueWriters.doubles();
        case STRING:
          return ValueWriters.strings();
        case FIXED:
          return ValueWriters.fixed(primitive.getFixedSize());
        case BYTES:
          return ValueWriters.bytes();
        default:
          throw new IllegalArgumentException("Unsupported type: " + primitive);
      }
    }
  }
}
