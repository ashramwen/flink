package org.apache.flink.streaming.connectors;

import kafka.common.TopicAndPartition;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.commons.collections.map.LinkedMap;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.checkpoint.CheckpointNotifier;
import org.apache.flink.streaming.api.checkpoint.CheckpointedAsynchronously;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.util.serialization.DeserializationSchema;
import org.apache.kafka.copied.clients.consumer.CommitType;
import org.apache.kafka.copied.clients.consumer.ConsumerConfig;
import org.apache.kafka.copied.clients.consumer.ConsumerRecord;
import org.apache.kafka.copied.clients.consumer.ConsumerRecords;
import org.apache.kafka.copied.clients.consumer.KafkaConsumer;
import org.apache.kafka.copied.common.PartitionInfo;
import org.apache.kafka.copied.common.TopicPartition;
import org.apache.kafka.copied.common.serialization.ByteArrayDeserializer;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class FlinkKafkaConsumer<T> extends RichParallelSourceFunction<T>
		implements CheckpointNotifier, CheckpointedAsynchronously<long[]> {

	public static Logger LOG = LoggerFactory.getLogger(FlinkKafkaConsumer.class);

	public final static String POLL_TIMEOUT = "flink.kafka.consumer.poll.timeout";
	public final static String OFFSET_STORE = "flink.kafka.consumer.offset.store";
	public final static long DEFAULT_POLL_TIMEOUT = 50;
	public final static OffsetStore DEFAULT_OFFSET_STORE = OffsetStore.ZOOKEEPER;

	private final String topic;
	private final Properties props;
	private final List<FlinkPartitionInfo> partitions;
	private final DeserializationSchema<T> valueDeserializer;

	private transient Fetcher fetcher;
	private final LinkedMap pendingCheckpoints = new LinkedMap();
	private long[] lastOffsets;
	private long[] commitedOffsets;
	private ZkClient zkClient;
	private long[] restoreToOffset;
	private final OffsetStore offsetStore;
	private final FetcherType fetcherType = FetcherType.LEGACY;

	public enum OffsetStore {
		ZOOKEEPER,
		/*
		Let Flink manage the offsets. It will store them in Zookeeper, in the same structure as Kafka 0.8.2.x
		Use this mode when using the source with Kafka 0.8.x brokers
		*/
		BROKER_COORDINATOR
		/*
		Use the mechanisms in Kafka to commit offsets. They will be send to the coordinator running
		in the broker.
		This works only starting from Kafka 0.8.3 (unreleased)
		*/
	}

	public enum FetcherType {
		LEGACY,  /* Use this fetcher for Kafka 0.8.1 brokers */
		INCLUDED /* This fetcher works with Kafka 0.8.2 and 0.8.3 */
	}

	public FlinkKafkaConsumer(String topic, DeserializationSchema<T> valueDeserializer, Properties props) {
		this.topic = topic;
		this.props = props; // TODO check for zookeeper properties
		this.valueDeserializer = valueDeserializer;

		KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<byte[], byte[]>(props, null, new ByteArrayDeserializer(), new ByteArrayDeserializer());
		List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
		if(partitionInfos == null) {
			throw new RuntimeException("The topic "+topic+" does not seem to exist");
		}
		partitions = new ArrayList<FlinkPartitionInfo>(partitionInfos.size());
		for(int i = 0; i < partitionInfos.size(); i++) {
			partitions.add(new FlinkPartitionInfo(partitionInfos.get(i)));
		}
		LOG.info("Topic {} has {} partitions", topic, partitions.size());
		consumer.close();

		OffsetStore os = DEFAULT_OFFSET_STORE;
		if(props.contains(OFFSET_STORE)) {
			String osString = props.getProperty(OFFSET_STORE);
			os = Enum.valueOf(OffsetStore.class, osString);
		}
		offsetStore = os;
	}

	// ----------------------------- Source ------------------------------

	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);

		// make sure that we take care of the committing
		props.setProperty("enable.auto.commit", "false");

		// create fetcher
		switch(fetcherType){
			case INCLUDED:
				fetcher = new IncludedFetcher(props);
				break;
			case LEGACY:
				fetcher = new LegacyFetcher(props);
				break;
			default:
				throw new RuntimeException("Requested unknown fetcher "+fetcher);
		}

		// tell which partitions we want:
		List<FlinkPartitionInfo> partitionsToSub = assignPartitions();
		LOG.info("This instance is going to subscribe to partitions {}", partitionsToSub);
		fetcher.partitionsToRead(partitionsToSub);

		// set up operator state
		lastOffsets = new long[partitions.size()];
		Arrays.fill(lastOffsets, -1);

		// prepare Zookeeper
		if(offsetStore == OffsetStore.ZOOKEEPER) {
			zkClient = new ZkClient(props.getProperty("zookeeper.connect"),
					Integer.valueOf(props.getProperty("zookeeper.session.timeout.ms", "6000")),
					Integer.valueOf(props.getProperty("zookeeper.connection.timeout.ms", "6000")),
					new KafkaZKStringSerializer());
		}
		commitedOffsets = new long[partitions.size()];


		// seek to last known pos, from restore request
		if(restoreToOffset != null) {
			LOG.info("Found offsets to restore to.");
			for(int i = 0; i < restoreToOffset.length; i++) {
				// if this fails because we are not subscribed to the topic, the partition assignment is not deterministic!
				fetcher.seek(new FlinkPartitionInfo(topic, i, null), restoreToOffset[i]);
			}
		} else {
			// no restore request. See what we have in ZK for this consumer group. In the non ZK case, Kafka will take care of this.
			if(offsetStore == OffsetStore.ZOOKEEPER) {
				for (FlinkPartitionInfo tp : partitionsToSub) {
					long offset = getOffset(zkClient, props.getProperty(ConsumerConfig.GROUP_ID_CONFIG), topic, tp.partition());
					if (offset != -1) {
						LOG.info("Offset for partition {} was set to {} in ZK. Seeking consumer to that position", tp.partition(), offset);
						fetcher.seek(tp, offset);
					}
				}
			}
		}

	}

	protected List<FlinkPartitionInfo> getPartitions() {
		return partitions;
	}

	public List<FlinkPartitionInfo> assignPartitions() {
		List<FlinkPartitionInfo> parts = getPartitions();
		List<FlinkPartitionInfo> partitionsToSub = new ArrayList<FlinkPartitionInfo>();

		int machine = 0;
		for(int i = 0; i < parts.size(); i++) {
			if(machine == getRuntimeContext().getIndexOfThisSubtask()) {
				partitionsToSub.add(parts.get(i));
			}
			machine++;

			if(machine == getRuntimeContext().getNumberOfParallelSubtasks()) {
				machine = 0;
			}
		}

		return partitionsToSub;
	}

	@Override
	public void run(SourceContext<T> sourceContext) throws Exception {
		fetcher.run(sourceContext, valueDeserializer, lastOffsets);


	}

	@Override
	public void cancel() {
		fetcher.stop();
		fetcher.close();
	}


	// ----------------------------- State ------------------------------
	@Override
	public void notifyCheckpointComplete(long checkpointId) throws Exception {
		if(fetcher == null) {
			LOG.info("notifyCheckpointComplete() called on uninitialized source");
			return;
		}

		LOG.info("Commit checkpoint {}", checkpointId);

		long[] checkpointOffsets;

		// the map may be asynchronously updates when snapshotting state, so we synchronize
		synchronized (pendingCheckpoints) {
			final int posInMap = pendingCheckpoints.indexOf(checkpointId);
			if (posInMap == -1) {
				LOG.warn("Unable to find pending checkpoint for id {}", checkpointId);
				return;
			}

			checkpointOffsets = (long[]) pendingCheckpoints.remove(posInMap);
			// remove older checkpoints in map:
			if (!pendingCheckpoints.isEmpty()) {
				for(int i = 0; i < posInMap; i++) {
					pendingCheckpoints.remove(0);
				}
			}
		}

		if (LOG.isInfoEnabled()) {
			LOG.info("Committing offsets {} to offset store: {}", Arrays.toString(checkpointOffsets), offsetStore);
		}

		if(offsetStore == OffsetStore.ZOOKEEPER) {
			setOffsetsInZooKeeper(checkpointOffsets);
		} else {
			Map<TopicPartition, Long> offsetsToCommit = new HashMap<TopicPartition, Long>();
			for(int i = 0; i < checkpointOffsets.length; i++) {
				if(checkpointOffsets[i] != -1) {
					offsetsToCommit.put(new TopicPartition(topic, i), checkpointOffsets[i]);
				}
			}
			fetcher.commit(offsetsToCommit);
		}
	}

	@Override
	public long[] snapshotState(long checkpointId, long checkpointTimestamp) throws Exception {
		if (lastOffsets == null) {
			LOG.warn("State snapshot requested on not yet opened source. Returning null");
			return null;
		}

		if (LOG.isInfoEnabled()) {
			LOG.info("Snapshotting state. Offsets: {}, checkpoint id {}, timestamp {}",
					Arrays.toString(lastOffsets), checkpointId, checkpointTimestamp);
		}

		long[] currentOffsets = Arrays.copyOf(lastOffsets, lastOffsets.length);

		// the map may be asynchronously updates when committing to Kafka, so we synchronize
		synchronized (pendingCheckpoints) {
			pendingCheckpoints.put(checkpointId, currentOffsets);
		}

		return currentOffsets;
	}

	@Override
	public void restoreState(long[] restoredOffsets) {
		restoreToOffset = restoredOffsets;
	}

	// ---------- Zookeeper communication ----------------

	private void setOffsetsInZooKeeper(long[] offsets) {
		for (int partition = 0; partition < offsets.length; partition++) {
			long offset = offsets[partition];
			if(offset != -1) {
				setOffset(partition, offset);
			}
		}
	}

	protected void setOffset(int partition, long offset) {
		// synchronize because notifyCheckpointComplete is called using asynchronous worker threads (= multiple checkpoints might be confirmed concurrently)
		synchronized (commitedOffsets) {
			if(commitedOffsets[partition] < offset) {
				LOG.info("Committed offsets {}, partition={}, offset={}", Arrays.toString(commitedOffsets), partition, offset);
				setOffset(zkClient, props.getProperty(ConsumerConfig.GROUP_ID_CONFIG), topic, partition, offset);
				commitedOffsets[partition] = offset;
			} else {
				LOG.debug("Ignoring offset {} for partition {} because it is already committed", offset, partition);
			}
		}
	}



	// the following two methods are static to allow access from the outside as well (Testcases)

	/**
	 * This method's code is based on ZookeeperConsumerConnector.commitOffsetToZooKeeper()
	 */
	public static void setOffset(ZkClient zkClient, String groupId, String topic, int partition, long offset) {
		LOG.info("Setting offset for partition {} of topic {} in group {} to offset {}", partition, topic, groupId, offset);
		TopicAndPartition tap = new TopicAndPartition(topic, partition);
		ZKGroupTopicDirs topicDirs = new ZKGroupTopicDirs(groupId, tap.topic());
		ZkUtils.updatePersistentPath(zkClient, topicDirs.consumerOffsetDir() + "/" + tap.partition(), Long.toString(offset));
	}

	public static long getOffset(ZkClient zkClient, String groupId, String topic, int partition) {
		TopicAndPartition tap = new TopicAndPartition(topic, partition);
		ZKGroupTopicDirs topicDirs = new ZKGroupTopicDirs(groupId, tap.topic());
		scala.Tuple2<Option<String>, Stat> data = ZkUtils.readDataMaybeNull(zkClient, topicDirs.consumerOffsetDir() + "/" + tap.partition());
		if(data._1().isEmpty()) {
			return -1;
		} else {
			return Long.valueOf(data._1().get());
		}
	}

	// ---------------------- Zookeeper Serializer copied from Kafka (because it has private access there)  -----------------
	public static class KafkaZKStringSerializer implements ZkSerializer {

		@Override
		public byte[] serialize(Object data) throws ZkMarshallingError {
			try {
				return ((String) data).getBytes("UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Object deserialize(byte[] bytes) throws ZkMarshallingError {
			if (bytes == null) {
				return null;
			} else {
				try {
					return new String(bytes, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}


}
