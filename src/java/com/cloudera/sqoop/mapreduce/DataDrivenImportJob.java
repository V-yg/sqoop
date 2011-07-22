/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.sqoop.mapreduce;

import java.io.IOException;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;

import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.config.ConfigurationHelper;
import com.cloudera.sqoop.lib.LargeObjectLoader;
import com.cloudera.sqoop.manager.ConnManager;
import com.cloudera.sqoop.manager.ImportJobContext;
import com.cloudera.sqoop.mapreduce.db.DBConfiguration;
import com.cloudera.sqoop.mapreduce.db.DataDrivenDBInputFormat;

/**
 * Actually runs a jdbc import job using the ORM files generated by the
 * sqoop.orm package. Uses DataDrivenDBInputFormat.
 */
public class DataDrivenImportJob extends ImportJobBase {

  public static final Log LOG = LogFactory.getLog(
      DataDrivenImportJob.class.getName());

  @SuppressWarnings("unchecked")
  public DataDrivenImportJob(final SqoopOptions opts) {
    super(opts, null, DataDrivenDBInputFormat.class, null, null);
  }

  public DataDrivenImportJob(final SqoopOptions opts,
      final Class<? extends InputFormat> inputFormatClass,
      ImportJobContext context) {
    super(opts, null, inputFormatClass, null, context);
  }

  @Override
  protected void configureMapper(Job job, String tableName,
      String tableClassName) throws IOException {
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      // For text files, specify these as the output types; for
      // other types, we just use the defaults.
      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(NullWritable.class);
    }

    job.setMapperClass(getMapperClass());
  }

  @Override
  protected Class<? extends Mapper> getMapperClass() {
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      return TextImportMapper.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.SequenceFile) {
      return SequenceFileImportMapper.class;
    }

    return null;
  }

  @Override
  protected Class<? extends OutputFormat> getOutputFormatClass()
      throws ClassNotFoundException {
    if (options.getFileLayout() == SqoopOptions.FileLayout.TextFile) {
      return RawKeyTextOutputFormat.class;
    } else if (options.getFileLayout()
        == SqoopOptions.FileLayout.SequenceFile) {
      return SequenceFileOutputFormat.class;
    }

    return null;
  }

  @Override
  protected void configureInputFormat(Job job, String tableName,
      String tableClassName, String splitByCol) throws IOException {
    ConnManager mgr = getContext().getConnManager();
    try {
      String username = options.getUsername();
      if (null == username || username.length() == 0) {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(), options.getConnectString(),
            options.getFetchSize());
      } else {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(), options.getConnectString(),
            username, options.getPassword(), options.getFetchSize());
      }

      if (null != tableName) {
        // Import a table.
        String [] colNames = options.getColumns();
        if (null == colNames) {
          colNames = mgr.getColumnNames(tableName);
        }

        String [] sqlColNames = null;
        if (null != colNames) {
          sqlColNames = new String[colNames.length];
          for (int i = 0; i < colNames.length; i++) {
            sqlColNames[i] = mgr.escapeColName(colNames[i]);
          }
        }

        // It's ok if the where clause is null in DBInputFormat.setInput.
        String whereClause = options.getWhereClause();

        // We can't set the class properly in here, because we may not have the
        // jar loaded in this JVM. So we start by calling setInput() with
        // DBWritable and then overriding the string manually.
        DataDrivenDBInputFormat.setInput(job, DBWritable.class,
            mgr.escapeTableName(tableName), whereClause,
            mgr.escapeColName(splitByCol), sqlColNames);
      } else {
        // Import a free-form query.
        String inputQuery = options.getSqlQuery();
        String sanitizedQuery = inputQuery.replace(
            DataDrivenDBInputFormat.SUBSTITUTE_TOKEN, " (1 = 1) ");
        String inputBoundingQuery = "SELECT MIN(" + splitByCol
            + "), MAX(" + splitByCol + ") FROM (" + sanitizedQuery + ") AS t1";
        DataDrivenDBInputFormat.setInput(job, DBWritable.class,
            inputQuery, inputBoundingQuery);
        new DBConfiguration(job.getConfiguration()).setInputOrderBy(
            splitByCol);
      }

      LOG.debug("Using table class: " + tableClassName);
      job.getConfiguration().set(ConfigurationHelper.getDbInputClassProperty(),
          tableClassName);

      job.getConfiguration().setLong(LargeObjectLoader.MAX_INLINE_LOB_LEN_KEY,
          options.getInlineLobLimit());

      LOG.debug("Using InputFormat: " + inputFormatClass);
      job.setInputFormatClass(inputFormatClass);
    } finally {
      try {
        mgr.close();
      } catch (SQLException sqlE) {
        LOG.warn("Error closing connection: " + sqlE);
      }
    }
  }
}

