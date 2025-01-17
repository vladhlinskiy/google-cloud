/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.cdap.plugin.gcp.bigquery.sink;

import com.google.cloud.bigquery.BigQuery;
import com.google.gson.JsonObject;
import com.google.gson.internal.bind.JsonTreeWriter;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.batch.OutputFormatProvider;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSinkContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This plugin allows users to write {@link StructuredRecord} entries to multiple Google Big Query tables.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name("BigQueryMultiTable")
@Description("Writes records to one or more Big Query tables. "
  + "BigQuery is Google's serverless, highly scalable, enterprise data warehouse. "
  + "Data is first written to a temporary location on Google Cloud Storage, then loaded into BigQuery from there.")
public class BigQueryMultiSink extends AbstractBigQuerySink {

  private static final String TABLE_PREFIX = "multisink.";

  private final BigQueryMultiSinkConfig config;

  public BigQueryMultiSink(BigQueryMultiSinkConfig config) {
    this.config = config;
  }

  @Override
  protected BigQueryMultiSinkConfig getConfig() {
    return config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    config.validate();
    super.configurePipeline(pipelineConfigurer);
  }

  @Override
  protected void prepareRunValidation(BatchSinkContext context) {
    config.validate();
  }

  @Override
  protected void prepareRunInternal(BatchSinkContext context, BigQuery bigQuery, String bucket) throws IOException {
    Map<String, String> arguments = new HashMap<>(context.getArguments().asMap());
    for (Map.Entry<String, String> argument : arguments.entrySet()) {
      String key = argument.getKey();
      if (!key.startsWith(TABLE_PREFIX)) {
        continue;
      }
      String tableName = key.substring(TABLE_PREFIX.length());
      // remove the database prefix, as BigQuery doesn't allow dots
      String[] split = tableName.split("\\.");
      if (split.length == 2) {
        tableName = split[1];
      }
      Schema tableSchema = Schema.parseJson(argument.getValue());

      String outputName = String.format("%s-%s", config.getReferenceName(), tableName);
      initOutput(context, bigQuery, outputName, tableName, tableSchema, bucket);
    }
  }

  @Override
  protected OutputFormatProvider getOutputFormatProvider(Configuration configuration,
                                                         String tableName,
                                                         Schema tableSchema) {
    return new MultiSinkOutputFormatProvider(configuration, tableName, tableSchema, config.getSplitField());
  }

  @Override
  public void transform(StructuredRecord input, Emitter<KeyValue<JsonObject, NullWritable>> emitter) {
    try (JsonTreeWriter writer = new JsonTreeWriter()) {
      writer.beginObject();
      for (Schema.Field recordField : Objects.requireNonNull(input.getSchema().getFields())) {
        BigQueryRecordToJson.write(writer, recordField.getName(), input.get(recordField.getName()),
                                   recordField.getSchema());
      }
      writer.endObject();
      emitter.emit(new KeyValue<>(writer.get().getAsJsonObject(), NullWritable.get()));
    } catch (IOException e) {
      throw new RuntimeException("Exception while converting structured record to json.", e);
    }
  }
}
