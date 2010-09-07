/*
 * Copyright (c) 2009 Concurrent, Inc.
 *
 * This work has been released into the public domain
 * by the copyright holder. This applies worldwide.
 *
 * In case this is not legally possible:
 * The copyright holder grants any entity the right
 * to use this work for any purpose, without any
 * conditions, unless such conditions are required by law.
 */

package de.leibert;

import cascading.Utils;
import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import com.google.common.base.Preconditions;
import me.prettyprint.cassandra.service.CassandraClient;
import me.prettyprint.cassandra.service.CassandraClientPool;
import me.prettyprint.cassandra.service.CassandraClientPoolFactory;
import me.prettyprint.cassandra.service.Keyspace;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.hadoop.io.BytesWritable;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import static me.prettyprint.cassandra.utils.StringUtils.bytes;

/**
 * The cassandra load function, it's a quick hack to allow loading data into cassandra.
 * Only byte serialized data can be stored with this function but this works for most types of
 * serialization, i.e. Thrift and Avro both support byte serialization. Therefore, another function
 * should be used to handle the serialization.
 */
public class CassandraLoadFunction extends BaseOperation implements Function {

  private static final Logger LOG = Logger.getLogger(CassandraLoadFunction.class.getName());

  private boolean isFullyQualified = false;
  private Fields keyField;
  private String table;
  private Fields[] valueFields;
  private String[] familyNames;
  private String[] columns;
  private String[] cassandraCluster;
  private transient CassandraClientPool pool;

  public CassandraLoadFunction(Fields keyFields, String table, String column, String familyName,
                               Fields valueFields,
                               String[] cassandraCluster) {
    this(keyFields, table, new String[]{column}, new String[]{familyName},
        Fields.fields(valueFields),
        cassandraCluster);
  }

  public CassandraLoadFunction(Fields keyFields, String table, String[] columns,
                               String[] familyNames,
                               Fields[] valueFields,
                               String[] cassandraCluster) {
    this.keyField = keyFields;
    //The column Names only holds the family Names.
    this.table = table;
    this.familyNames = familyNames;
    this.valueFields = valueFields;
    this.cassandraCluster = cassandraCluster;
    this.columns = columns;
    validate();
  }

  public CassandraLoadFunction(Fields keyField, Fields valueFields) {
    this(keyField, Fields.fields(valueFields));
  }

  public CassandraLoadFunction(Fields keyField, Fields[] valueFields) {
    this.isFullyQualified = true;
    this.keyField = keyField;
    this.valueFields = valueFields;
    validate();
  }

  private void validate() {
    if (keyField.size() != 1) {
      throw new IllegalArgumentException("may only have one key field, found: " + keyField.print());
    }
  }

  @Override
  public void operate(FlowProcess flowProcess, FunctionCall functionCall) {
    if (pool == null) {
      pool = CassandraClientPoolFactory.INSTANCE.get();
    }

    TupleEntry input = functionCall.getArguments();
    Tuple key = input.selectTuple(keyField);
    CassandraClient client = null;
    try {
      client = pool.borrowClient(cassandraCluster);
      Keyspace keyspace = client.getKeyspace(table, ConsistencyLevel.QUORUM);
      ColumnPath columnPath = new ColumnPath(familyNames[0]);

      for (int i = 0; i < valueFields.length; i++) {
        Fields fieldSelector = valueFields[i];
        TupleEntry values = input.selectEntry(fieldSelector);

        for (int j = 0; j < values.getFields().size(); j++) {
          byte[] data = bytes(columns[j]);
          columnPath.setColumn(data);

          Tuple tuple = values.getTuple();

          Comparable c = tuple.get(j);
          byte[] hvalue;
          if (c instanceof BytesWritable) {
            BytesWritable w = (BytesWritable) c;
            hvalue = new byte[w.getLength()];
            System.arraycopy(w.getBytes(), 0,
                hvalue, 0, w.getLength());
          } else {
            throw new UnsupportedOperationException(
                "Only bytes are allowed to be written to cassandra");
          }
          keyspace.insert(key.getString(0), columnPath, hvalue);

          //This is important, bc we need to ensure we have the correct client handle.
          client = keyspace.getClient();
          flowProcess.increment(Utils.ROWS.IMPORTED, 1);
        }
      }
    } catch (Exception e) {
      flowProcess.increment(Utils.ROWS.FAILED_AND_SKIPPED, 1);
      LOG.log(Level.WARNING, "Exception  while trying to insert in Cassandra:", e);
    } finally {
      try {
        pool.releaseClient(client);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Exception  while trying to insert in Cassandra:", e);

      }
    }
  }
}