/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.flink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.apache.flink.types.Row;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.flink.data.FlinkParquetWriters;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.FileAppenderFactory;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

class RowTaskWriterFactory implements TaskWriterFactory<Row> {
  private final Schema schema;
  private final PartitionSpec spec;
  private final LocationProvider locations;
  private final FileIO io;
  private final EncryptionManager encryptionManager;
  private final long targetFileSizeBytes;
  private final FileFormat format;
  private final FileAppenderFactory<Row> appenderFactory;

  private OutputFileFactory outputFileFactory;

  RowTaskWriterFactory(Schema schema,
                       PartitionSpec spec,
                       LocationProvider locations,
                       FileIO io,
                       EncryptionManager encryptionManager,
                       long targetFileSizeBytes,
                       FileFormat format,
                       Map<String, String> tableProperties) {
    this.schema = schema;
    this.spec = spec;
    this.locations = locations;
    this.io = io;
    this.encryptionManager = encryptionManager;
    this.targetFileSizeBytes = targetFileSizeBytes;
    this.format = format;
    this.appenderFactory = new FlinkFileAppenderFactory(schema, tableProperties);
  }

  @Override
  public void initialize(int taskId, int attemptId) {
    this.outputFileFactory = new OutputFileFactory(spec, format, locations, io, encryptionManager, taskId, attemptId);
  }

  @Override
  public TaskWriter<Row> create() {
    Preconditions.checkNotNull(outputFileFactory,
        "The outputFileFactory shouldn't be null if we have invoked the initialize().");

    if (spec.fields().isEmpty()) {
      return new UnpartitionedWriter<>(spec, format, appenderFactory, outputFileFactory, io, targetFileSizeBytes);
    } else {
      return new RowPartitionedFanoutWriter(spec, format, appenderFactory, outputFileFactory,
          io, targetFileSizeBytes, schema);
    }
  }

  private static class RowPartitionedFanoutWriter extends PartitionedFanoutWriter<Row> {

    private final PartitionKey partitionKey;
    private final RowWrapper rowWrapper;

    RowPartitionedFanoutWriter(PartitionSpec spec, FileFormat format, FileAppenderFactory<Row> appenderFactory,
                               OutputFileFactory fileFactory, FileIO io, long targetFileSize, Schema schema) {
      super(spec, format, appenderFactory, fileFactory, io, targetFileSize);
      this.partitionKey = new PartitionKey(spec, schema);
      this.rowWrapper = new RowWrapper(schema.asStruct());
    }

    @Override
    protected PartitionKey partition(Row row) {
      partitionKey.partition(rowWrapper.wrap(row));
      return partitionKey;
    }
  }

  private static class FlinkFileAppenderFactory implements FileAppenderFactory<Row> {
    private final Schema schema;
    private final Map<String, String> props;

    private FlinkFileAppenderFactory(Schema schema, Map<String, String> props) {
      this.schema = schema;
      this.props = props;
    }

    @Override
    public FileAppender<Row> newAppender(OutputFile outputFile, FileFormat format) {
      MetricsConfig metricsConfig = MetricsConfig.fromProperties(props);
      try {
        switch (format) {
          case PARQUET:
            return Parquet.write(outputFile)
                .createWriterFunc(FlinkParquetWriters::buildWriter)
                .setAll(props)
                .metricsConfig(metricsConfig)
                .schema(schema)
                .overwrite()
                .build();

          case AVRO:
            // TODO add the Avro writer building once RowDataWrapper is ready.
          case ORC:
          default:
            throw new UnsupportedOperationException("Cannot write unknown file format: " + format);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
