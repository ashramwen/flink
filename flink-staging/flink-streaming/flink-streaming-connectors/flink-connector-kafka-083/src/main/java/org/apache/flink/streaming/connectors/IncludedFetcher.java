package org.apache.flink.streaming.connectors;

import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.util.serialization.DeserializationSchema;
import org.apache.kafka.copied.clients.consumer.CommitType;
import org.apache.kafka.copied.clients.consumer.ConsumerRecord;
import org.apache.kafka.copied.clients.consumer.ConsumerRecords;
import org.apache.kafka.copied.clients.consumer.KafkaConsumer;
import org.apache.kafka.copied.common.TopicPartition;
import org.apache.kafka.copied.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;

public class IncludedFetcher implements Fetcher {
	public static Logger LOG = LoggerFactory.getLogger(IncludedFetcher.class);

	final KafkaConsumer<byte[], byte[]> fetcher;
	final Properties props;
	boolean running = true;

	public IncludedFetcher(Properties props) {
		this.props = props;
		fetcher = new KafkaConsumer<byte[], byte[]>(props, null, new ByteArrayDeserializer(), new ByteArrayDeserializer());
	}

	@Override
	public void partitionsToRead(List<FlinkPartitionInfo> partitions) {
		TopicPartition[] partitionsArray = new TopicPartition[partitions.size()];
		int i = 0;
		for(FlinkPartitionInfo p: partitions) {
			partitionsArray[i++] = new TopicPartition(p.topic(), p.partition());
		}
		fetcher.subscribe(partitionsArray);
	}

	@Override
	public void close() {
		synchronized (fetcher) {
			fetcher.close();
		}
	}

	@Override
	public <T> void run(SourceFunction.SourceContext<T> sourceContext, DeserializationSchema<T> valueDeserializer, long[] lastOffsets) {
		long pollTimeout = FlinkKafkaConsumer.DEFAULT_POLL_TIMEOUT;
		if(props.contains(FlinkKafkaConsumer.POLL_TIMEOUT)) {
			pollTimeout = Long.valueOf(props.getProperty(FlinkKafkaConsumer.POLL_TIMEOUT));
		}
		while(running) {
			synchronized (fetcher) {
				ConsumerRecords<byte[], byte[]> consumed = fetcher.poll(pollTimeout);
				if(!consumed.isEmpty()) {
					synchronized (sourceContext.getCheckpointLock()) {
						for(ConsumerRecord<byte[], byte[]> record : consumed) {
							T value = valueDeserializer.deserialize(record.value());
							sourceContext.collect(value);
							lastOffsets[record.partition()] = record.offset();
						}
					}
				}
			}
		}
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public void commit(Map<TopicPartition, Long> offsetsToCommit) {
		synchronized (fetcher) {
			fetcher.commit(offsetsToCommit, CommitType.SYNC);
		}
	}

	@Override
	public void seek(FlinkPartitionInfo topicPartition, long offset) {
		fetcher.seek(new TopicPartition(topicPartition.topic(), topicPartition.partition()), offset);
	}
}
