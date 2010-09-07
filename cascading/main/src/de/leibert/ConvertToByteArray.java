package de.leibert;

import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import org.apache.hadoop.io.BytesWritable;
import org.apache.thrift.TBase;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TSerializer;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TType;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Logger;

public class ConvertToByteArray<T extends TBase> extends BaseOperation implements Function {

  public static final String LIST_DELIM = ",";
  public static final String KV_DELIM = ":";
  public static final Logger LOG = Logger.getLogger(ConvertToByteArray.class.getName());
  private transient TSerializer serializer;
  private Map<Fields, ? extends TFieldIdEnum> mapping;
  private HashMap<TFieldIdEnum, Class<?>> clazzMap = new HashMap();
  private T templateInstance;
  private Class<T> tbase;
  private Map<? extends TFieldIdEnum, FieldMetaData> thriftMetaDataMap = null;
  private boolean failOnError = true;
  private long errorCount = 0L;

  public ConvertToByteArray(final Map<Fields, ? extends TFieldIdEnum> mapping, Fields outgoing,
                            final Class<T> tbase) {
    super(mapping.size(), outgoing);
    this.tbase = tbase;
    thriftMetaDataMap = FieldMetaData.getStructMetaDataMap(tbase);
    try {
      templateInstance = tbase.newInstance();
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    for (TFieldIdEnum f : mapping.values()) {
      String fieldName = f.getFieldName();

      try {
        Field field = tbase.getField(fieldName);
        clazzMap.put(f, field.getType());

      } catch (NoSuchFieldException e) {
        throw new RuntimeException("Error, field not found!");

      }
      this.mapping = mapping;
    }
  }

  @Override
  public void operate (FlowProcess flowProcess, FunctionCall functionCall) {
    if (serializer == null) {
      serializer = new TSerializer(new TBinaryProtocol.Factory());
    }
    if (templateInstance == null) {
      throw new RuntimeException("template instance was null! Type found:" + templateInstance.getClass().getName());
    }

    TBase t = null;
    TupleEntry te = functionCall.getArguments();

    try {
      t = tbase.newInstance();

    } catch (Throwable e) {
      throw new RuntimeException("Error, thrift class not instantiable:" + tbase.getClass().getName(), e);
    }

    for (Fields field : mapping.keySet()) {
      final TFieldIdEnum id = mapping.get(field);

      final Tuple tuple = te.selectTuple(field);
      final Object c = getCoercedValue(id, tuple);
      if (c != null) {
        t.setFieldValue(id.getThriftFieldId(), c);
      }
    }

    byte[] payload;
    try {
      payload = serializer.serialize(t);

    } catch (TException e) {
      flowProcess.increment("ERROR", "THRIFT_SERIALIZATION", 1);
      return;
    }

    BytesWritable bw = new BytesWritable(payload);
    Tuple result = new Tuple();
    result.add(bw);
    functionCall.getOutputCollector().add(result);
  }

  public Object getCoercedValue (TFieldIdEnum id, Tuple t) {
    final Class<?> clazz = clazzMap.get(id);
    final String tsr = t.getString(0);
    if ((tsr == null) || tsr.equals("")) {
      return null;
    }

    if (clazz == Double.class || clazz == double.class) {
      return t.getDouble(0);
    } else if (clazz == String.class) {
      return t.getString(0);
    } else if (clazz == Short.class || clazz == short.class) {
      return t.getShort(0);
    } else if (clazz == Integer.class || clazz == int.class) {
      return t.getInteger(0);
    } else if (clazz == Float.class || clazz == float.class) {
      return t.getFloat(0);
    } else if (clazz == Boolean.class || clazz == boolean.class) {
      return t.getBoolean(0);
    } else if (clazz == Long.class || clazz == long.class) {
      return t.getLong(0);
    } else if (Collection.class.isAssignableFrom(clazz)) {
      Collection collection = new LinkedList();
      populateCollection(t.getString(0), id, collection);
      return collection;
    } else if (Map.class.isAssignableFrom(clazz)) {
      Map map = new HashMap();
      populateMap(t.getString(0), id, map);
      return map;
    } else {
      return t.get(0);
    }

  }

  public void populateMap(final String entry, TFieldIdEnum id, Map map) {
    final FieldMetaData metaData = thriftMetaDataMap.get(id);

    String[] elements = entry.split(LIST_DELIM);
    FieldValueMetaData fieldValueMetaData = metaData.valueMetaData;
    byte keyType, valueType;
    if (fieldValueMetaData instanceof MapMetaData) {
      keyType = ((MapMetaData) fieldValueMetaData).keyMetaData.type;
      valueType = ((MapMetaData) fieldValueMetaData).valueMetaData.type;
    } else {
      throw new RuntimeException("Error, type:" + fieldValueMetaData.getClass().toString() + " not supported!");
    }

    for (String element : elements) {

      Object key = null;
      Object val = null;
      try {
        final String trimmed = element.trim();
        final String[] parts = trimmed.split(KV_DELIM);
        final String unparsedKey = parts[0];
        final String unparsedValue = parts[1];
        switch (keyType) {
          case TType.I16:
            key = Short.parseShort(unparsedKey);
            break;
          case TType.I32:
            key = Integer.parseInt(unparsedKey);
            break;
          case TType.I64:
            key = Long.parseLong(unparsedKey);
            break;
          case TType.DOUBLE:
            key = Double.parseDouble(unparsedKey);
            break;
          case TType.STRING:
            key = unparsedKey;
            break;
        }

        switch (valueType) {
          case TType.I16:
            val = Short.parseShort(unparsedValue);
            break;
          case TType.I32:
            val = Integer.parseInt(unparsedValue);
            break;
          case TType.I64:
            val = Long.parseLong(unparsedValue);
            break;
          case TType.DOUBLE:
            val = Double.parseDouble(unparsedValue);
            break;
          case TType.STRING:
            val = unparsedValue;
            break;
        }
      } catch (Throwable t) {
        errorCount++;
        LOG.warning(String.format("Error parsing %s to type: %s", element, metaData.requirementType));
      }
      if (key == null && failOnError) {
        throw new RuntimeException("Error occurred while trying to parse list. Shutting down!");
      }
      map.put(key, val);
    }

  }

  public void populateCollection(final String entry, TFieldIdEnum id, Collection collection) {
    final FieldMetaData metaData = thriftMetaDataMap.get(id);
    String[] elements = entry.split(LIST_DELIM);
    FieldValueMetaData fieldValueMetaData = metaData.valueMetaData;
    byte type;
    if (fieldValueMetaData instanceof ListMetaData) {
      type = ((ListMetaData) fieldValueMetaData).elemMetaData.type;
    } else {
      throw new RuntimeException("Error, type:" + fieldValueMetaData.getClass().toString() + " not supported!");
    }

    for (String element : elements) {
      final String trimmed = element.trim();
      Object val = null;
      try {
        switch (type) {
          case TType.I16:
            val = Short.parseShort(trimmed);
            break;
          case TType.I32:
            val = Integer.parseInt(trimmed);
            break;
          case TType.I64:
            val = Long.parseLong(trimmed);
            break;
          case TType.DOUBLE:
            val = Double.parseDouble(trimmed);
            break;
          case TType.STRING:
            val = trimmed;
            break;
        }
      } catch (Throwable t) {
        errorCount++;
        LOG.warning(String.format("Error parsing %s to type: %s", trimmed, metaData.requirementType));
      }
      if (val == null && failOnError) {
        throw new RuntimeException("Error occurred while trying to parse list. Shutting down!");
      }
      collection.add(val);
    }
  }

  public static void testDeserialize (String prefix, Class<? extends TBase> template, byte[] bytes) throws Exception {
    TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
    TBase instance = template.newInstance();
    deserializer.deserialize(instance, bytes);
    System.out.println(prefix + instance + "\t[" + bytes.length + "]");
  }

  public static <T extends TBase> T deserialize(T t, byte[] bytes) throws Exception {
    TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
    deserializer.deserialize(t, bytes);
    return t;
  }
}