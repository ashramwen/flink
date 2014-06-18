package eu.stratosphere.api.datastream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import eu.stratosphere.api.java.functions.FlatMapFunction;
import eu.stratosphere.api.java.tuple.Tuple;
import eu.stratosphere.api.java.tuple.Tuple1;
import eu.stratosphere.api.java.typeutils.TypeExtractor;
import eu.stratosphere.streaming.api.JobGraphBuilder;
import eu.stratosphere.streaming.api.StreamCollector;
import eu.stratosphere.streaming.api.invokable.DefaultSinkInvokable;
import eu.stratosphere.streaming.api.invokable.UserSinkInvokable;
import eu.stratosphere.streaming.api.invokable.UserSourceInvokable;
import eu.stratosphere.streaming.api.streamrecord.ArrayStreamRecord;
import eu.stratosphere.streaming.api.streamrecord.StreamRecord;
import eu.stratosphere.streaming.faulttolerance.FaultToleranceType;
import eu.stratosphere.streaming.util.ClusterUtil;
import eu.stratosphere.types.TypeInformation;
import eu.stratosphere.util.Collector;

public class StreamExecutionEnvironment {
	JobGraphBuilder jobGraphBuilder;

	private static final int BATCH_SIZE = 1;

	public StreamExecutionEnvironment() {
		jobGraphBuilder = new JobGraphBuilder("jobGraph", FaultToleranceType.NONE);
	}

	private static class DummySource extends UserSourceInvokable<Tuple1<String>> {
		private static final long serialVersionUID = 1L;

		@Override
		public void invoke(Collector<Tuple1<String>> collector) throws Exception {

			for (int i = 0; i < 10; i++) {
				collector.collect(new Tuple1<String>("source"));
			}
		}

	}

	public <T extends Tuple, R extends Tuple> DataStream<R> addFlatMapFunction(
			DataStream<T> inputStream, final FlatMapFunction<T, R> flatMapper) {
		DataStream<R> returnStream = new DataStream<R>(this);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos;
		try {
			oos = new ObjectOutputStream(baos);
			oos.writeObject(flatMapper);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		jobGraphBuilder.setTask(returnStream.getId(), new FlatMapInvokable<T, R>(flatMapper),
				"flatMap", baos.toByteArray());

		jobGraphBuilder.shuffleConnect(inputStream.getId(), returnStream.getId());

		return returnStream;
	}

	// public <T, R> DataStream<R> addMapFunction(DataStream<T> inputStream,
	// final MapFunction<T, R> mapper, TypeInformation<R> returnType) {
	// DataStream<R> returnStream = new DataStream<R>(this, returnType);
	//
	// jobGraphBuilder.setTask(inputStream.getId(), new UserTaskInvokable() {
	// private static final long serialVersionUID = 1L;
	// private StreamRecord outRecord = new ArrayStreamRecord(BATCH_SIZE);
	//
	// @Override
	// public void invoke(StreamRecord record) throws Exception {
	// int batchSize = record.getBatchSize();
	// for (int i = 0; i < batchSize; i++) {
	// T tuple = (T) record.getTuple(i);
	// R resultTuple = mapper.map(tuple);
	// outRecord.setTuple(i, (Tuple) resultTuple);
	// }
	// }
	// });
	//
	// jobGraphBuilder.shuffleConnect(inputStream.getId(),
	// returnStream.getId());
	// return returnStream;
	// }

	public static final class DummySink extends UserSinkInvokable<Tuple1<String>> {
		private static final long serialVersionUID = 1L;

		@Override
		public void invoke(StreamRecord record, StreamCollector<Tuple> collector) throws Exception {
			for (Tuple tuple : record.getBatchIterable()) {
				System.out.println(tuple);
			}
		}

	}

	public <T extends Tuple, R extends Tuple> DataStream<R> addDummySink(DataStream<T> inputStream) {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos;
		try {
			oos = new ObjectOutputStream(baos);
			oos.writeObject(new DummySink());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		jobGraphBuilder.setSink("sink", new DummySink(), "sink", baos.toByteArray());

		jobGraphBuilder.shuffleConnect(inputStream.getId(), "sink");
		return new DataStream<R>(this);
	}

	public void execute() {
		ClusterUtil.runOnMiniCluster(jobGraphBuilder.getJobGraph());
	}

	public DataStream<Tuple1<String>> setDummySource() {
		DataStream<Tuple1<String>> returnStream = new DataStream<Tuple1<String>>(this);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos;
		try {
			oos = new ObjectOutputStream(baos);
			oos.writeObject(new DummySource());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		jobGraphBuilder.setSource(returnStream.getId(), new DummySource(), "source",
				baos.toByteArray());
		return returnStream;
	}

	public JobGraphBuilder jobGB() {
		return jobGraphBuilder;
	}
}
