package de.leibert;

import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Buffer;
import cascading.operation.BufferCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;

public class CompleteStatsBuffer extends BaseOperation implements Buffer {
  private final Fields target;

  public enum TYPE {
    N, MAX, MIN, MEAN, DEVIATION, VARIANCE, KURTOSIS, SKEWNESS
  };

  private Collection<TYPE> types;

  protected static Fields buildFieldsFromTypes(Fields declared, TYPE... types) {
    Fields result = new Fields();
    for (int i = 0; i < declared.size(); i++) {
      String f = (String) declared.get(i);
      result = result.append(new Fields(f));
    }
    if (types.length == 0) {
      types = TYPE.values();
    }
    for (TYPE t : types) {
      result = result.append(new Fields(t.toString()));
    }
    return result;
  }

  public CompleteStatsBuffer(Fields declared, Fields target, TYPE... types) {
    super(Fields.ARGS.size(), buildFieldsFromTypes(declared));
    this.target = target;
    if (types.length == 0) {
      types = TYPE.values();
    }
    this.types = Arrays.asList(types);

  }

  public void operate(FlowProcess flowProcess, BufferCall bufferCall) {
    Iterator<TupleEntry> arguments = bufferCall.getArgumentsIterator();
    List<Tuple> allEntries = new LinkedList();
    DescriptiveStatistics stats = new DescriptiveStatistics();

    while (arguments.hasNext()) {
      TupleEntry argument = arguments.next();
      Double val = argument.getDouble(target.get(0));
      stats.addValue(val);

      Tuple current = new Tuple();
      for (int i = 0; i < argument.getFields().size(); i++) {
        Comparable c = argument.get(i);
        current.add(c);
      }
      allEntries.add(current);
    }

    for (int i = 0; i < allEntries.size(); i++) {
      Tuple result = allEntries.get(i);

      for (TYPE t : types) {
        if (t.equals(TYPE.MAX)) {
          Double max = stats.getMax();
          result.add(max);
        } else if (t.equals(TYPE.MIN)) {
          Double min = stats.getMin();
          result.add(min);
        } else if (t.equals(TYPE.MEAN)) {
          Double mean = stats.getMean();
          result.add(mean);
        } else if (t.equals(TYPE.DEVIATION)) {
          Double std = stats.getStandardDeviation();
          result.add(std);
        } else if (t.equals(TYPE.N)) {
          Long n = stats.getN();
          result.add(n);
        } else if (t.equals(TYPE.KURTOSIS)) {
          Double kurt = stats.getKurtosis();
          result.add(kurt);
        } else if (t.equals(TYPE.SKEWNESS)) {
          Double skew = stats.getSkewness();
          result.add(skew);
        } else if (t.equals(TYPE.VARIANCE)) {
          Double variance = stats.getVariance();
          result.add(variance);
        }
      }
      bufferCall.getOutputCollector().add(result);
      bufferCall.getOutputCollector().close();
    }
  }

}
