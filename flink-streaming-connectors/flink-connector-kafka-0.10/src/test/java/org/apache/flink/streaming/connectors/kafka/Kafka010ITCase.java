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

package org.apache.flink.streaming.connectors.kafka;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.TypeInfoParser;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamSink;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.tasks.StreamTaskState;
import org.apache.flink.streaming.util.serialization.KeyedSerializationSchema;
import org.apache.flink.streaming.util.serialization.SerializationSchema;
import org.apache.flink.streaming.util.serialization.TypeInformationSerializationSchema;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


public class Kafka010ITCase extends KafkaConsumerTestBase {

	// ------------------------------------------------------------------------
	//  Suite of Tests
	// ------------------------------------------------------------------------


	@Test(timeout = 60000)
	public void testFailOnNoBroker() throws Exception {
		runFailOnNoBrokerTest();
	}

	@Test(timeout = 60000)
	public void testConcurrentProducerConsumerTopology() throws Exception {
		runSimpleConcurrentProducerConsumerTopology();
	}

//	@Test(timeout = 60000)
//	public void testPunctuatedExplicitWMConsumer() throws Exception {
//		runExplicitPunctuatedWMgeneratingConsumerTest(false);
//	}

//	@Test(timeout = 60000)
//	public void testPunctuatedExplicitWMConsumerWithEmptyTopic() throws Exception {
//		runExplicitPunctuatedWMgeneratingConsumerTest(true);
//	}

	@Test(timeout = 60000)
	public void testKeyValueSupport() throws Exception {
		runKeyValueTest();
	}

	// --- canceling / failures ---

	@Test(timeout = 60000)
	public void testCancelingEmptyTopic() throws Exception {
		runCancelingOnEmptyInputTest();
	}

	@Test(timeout = 60000)
	public void testCancelingFullTopic() throws Exception {
		runCancelingOnFullInputTest();
	}

	@Test(timeout = 60000)
	public void testFailOnDeploy() throws Exception {
		runFailOnDeployTest();
	}


	// --- source to partition mappings and exactly once ---

	@Test(timeout = 60000)
	public void testOneToOneSources() throws Exception {
		runOneToOneExactlyOnceTest();
	}

	@Test(timeout = 60000)
	public void testOneSourceMultiplePartitions() throws Exception {
		runOneSourceMultiplePartitionsExactlyOnceTest();
	}

	@Test(timeout = 60000)
	public void testMultipleSourcesOnePartition() throws Exception {
		runMultipleSourcesOnePartitionExactlyOnceTest();
	}

	// --- broker failure ---

	@Test(timeout = 60000)
	public void testBrokerFailure() throws Exception {
		runBrokerFailureTest();
	}

	// --- special executions ---

	@Test(timeout = 60000)
	public void testBigRecordJob() throws Exception {
		runBigRecordTestTopology();
	}

	@Test(timeout = 60000)
	public void testMultipleTopics() throws Exception {
		runProduceConsumeMultipleTopics();
	}

	@Test(timeout = 60000)
	public void testAllDeletes() throws Exception {
		runAllDeletesTest();
	}

	@Test(timeout = 60000)
	public void testMetricsAndEndOfStream() throws Exception {
		runEndOfStreamTest();
	}

	@Test
	public void testJsonTableSource() throws Exception {
		String topic = UUID.randomUUID().toString();

		// Names and types are determined in the actual test method of the
		// base test class.
		Kafka010JsonTableSource tableSource = new Kafka010JsonTableSource(
				topic,
				standardProps,
				new String[] {
						"long",
						"string",
						"boolean",
						"double",
						"missing-field"},
				new TypeInformation<?>[] {
						BasicTypeInfo.LONG_TYPE_INFO,
						BasicTypeInfo.STRING_TYPE_INFO,
						BasicTypeInfo.BOOLEAN_TYPE_INFO,
						BasicTypeInfo.DOUBLE_TYPE_INFO,
						BasicTypeInfo.LONG_TYPE_INFO });

		// Don't fail on missing field, but set to null (default)
		tableSource.setFailOnMissingField(false);

		runJsonTableSource(topic, tableSource);
	}

	@Test
	public void testJsonTableSourceWithFailOnMissingField() throws Exception {
		String topic = UUID.randomUUID().toString();

		// Names and types are determined in the actual test method of the
		// base test class.
		Kafka010JsonTableSource tableSource = new Kafka010JsonTableSource(
				topic,
				standardProps,
				new String[] {
						"long",
						"string",
						"boolean",
						"double",
						"missing-field"},
				new TypeInformation<?>[] {
						BasicTypeInfo.LONG_TYPE_INFO,
						BasicTypeInfo.STRING_TYPE_INFO,
						BasicTypeInfo.BOOLEAN_TYPE_INFO,
						BasicTypeInfo.DOUBLE_TYPE_INFO,
						BasicTypeInfo.LONG_TYPE_INFO });

		// Don't fail on missing field, but set to null (default)
		tableSource.setFailOnMissingField(true);

		try {
			runJsonTableSource(topic, tableSource);
			fail("Did not throw expected Exception");
		} catch (Exception e) {
			Throwable rootCause = e.getCause().getCause().getCause();
			assertTrue("Unexpected root cause", rootCause instanceof IllegalStateException);
		}
	}

	/**
	 * Kafka 0.10 specific test, ensuring Timestamps are properly written to and read from Kafka
	 */
	@Test(timeout = 60000)
	public void testTimestamps() throws Exception {

		// ---------- Produce an event time stream into Kafka -------------------

		StreamExecutionEnvironment env = StreamExecutionEnvironment.createRemoteEnvironment("localhost", flinkPort);
		env.setParallelism(1);
		env.getConfig().setRestartStrategy(RestartStrategies.noRestart());
		env.getConfig().disableSysoutLogging();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		DataStream<Long> streamWithTimestamps = env.addSource(new SourceFunction<Long>() {
			boolean running = true;

			@Override
			public void run(SourceContext<Long> ctx) throws Exception {
				long i = 0;
				while(running) {
					ctx.collectWithTimestamp(i, i*2);
					if(i++ == 1000L) {
						running = false;
					}
				}
			}

			@Override
			public void cancel() {
				running = false;
			}
		});

		TypeInformationSerializationSchema<Long> longSer = new TypeInformationSerializationSchema<>(TypeInfoParser.<Long>parse("Long"), env.getConfig());
		FlinkKafkaProducer010.FlinkKafkaProducer010Configuration prod = FlinkKafkaProducer010.writeToKafka(streamWithTimestamps, "tstopic", longSer, standardProps);
		prod.setWriteTimestampToKafka(true);
		env.execute("Produce some");

		// ---------- Consume stream from Kafka -------------------

		env = StreamExecutionEnvironment.createRemoteEnvironment("localhost", flinkPort);
		env.setParallelism(1);
		env.getConfig().setRestartStrategy(RestartStrategies.noRestart());
		env.getConfig().disableSysoutLogging();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		DataStream<Long> stream = env.addSource(new FlinkKafkaConsumer010<>("tstopic", longSer, standardProps));
		GenericTypeInfo<Object> objectTypeInfo = new GenericTypeInfo<>(Object.class);
		stream.transform("timestamp validating operator", objectTypeInfo, new TimestampValidatingOperator());

		env.execute("Consume again");
	}

	private static class TimestampValidatingOperator extends StreamSink<Long> {

		public TimestampValidatingOperator() {
			super(new SinkFunction<Long>() {
				@Override
				public void invoke(Long value) throws Exception {
					throw new RuntimeException("Unexpected");
				}
			});
		}

		@Override
		public void processElement(StreamRecord<Long> element) throws Exception {
			System.out.println("Received " + element);
		}

		@Override
		public void processWatermark(Watermark mark) throws Exception {
			System.out.println("Received watermark " + mark);
		}
	}

}
