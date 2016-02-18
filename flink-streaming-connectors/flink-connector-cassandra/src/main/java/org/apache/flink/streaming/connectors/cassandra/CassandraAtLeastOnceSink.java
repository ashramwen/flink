/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.streaming.connectors.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.streaming.runtime.operators.CheckpointCommitter;
import org.apache.flink.streaming.runtime.operators.GenericAtLeastOnceSink;

/**
 * Sink that emits its input elements into a Cassandra database. This sink is integrated with the checkpointing
 * mechanism to provide near at-least-once semantics.
 * <p/>
 * Incoming records are stored within a {@link org.apache.flink.runtime.state.AbstractStateBackend}, and only committed if a
 * checkpoint is completed. Should a job fail, while data is being committed, no exactly once guarantee can be made.
 *
 * @param <IN> Type of the elements emitted by this sink
 */
public class CassandraAtLeastOnceSink<IN extends Tuple> extends GenericAtLeastOnceSink<IN> {
	private final String host;
	private final String insertQuery;

	private transient Cluster cluster;
	private transient Session session;
	private transient PreparedStatement preparedStatement;

	private transient Throwable exception = null;
	private transient final FutureCallback<ResultSet> callback;

	public CassandraAtLeastOnceSink(String host, String insertQuery, CheckpointCommitter committer, TypeSerializer<IN> serializer) {
		super(committer, serializer);
		if (host == null) {
			throw new IllegalArgumentException("Host argument must not be null.");
		}
		if (insertQuery == null) {
			throw new IllegalArgumentException("Insert query argument must not be null.");
		}
		this.host = host;
		this.insertQuery = insertQuery;
		this.callback = new FutureCallback<ResultSet>() {
			@Override
			public void onSuccess(ResultSet resultSet) {
			}

			@Override
			public void onFailure(Throwable throwable) {
				exception = throwable;
			}
		};
	}

	@Override
	public void close() throws Exception {
		super.close();
		try {
			session.close();
		} catch (Exception e) {
			LOG.error("Error while closing session.", e);
		}
		try {
			cluster.close();
		} catch (Exception e) {
			LOG.error("Error while closing cluster.", e);
		}
	}

	@Override
	public void open() throws Exception {
		super.open();
		cluster = Cluster.builder().addContactPoint(host).build();
		session = cluster.connect();
		preparedStatement = session.prepare(insertQuery);
	}

	@Override
	protected void sendValue(Iterable<IN> values) throws Exception {
		//verify that no query failed until now
		if (exception != null) {
			throw new Exception(exception);
		}
		//set values for prepared statement
		for (IN value : values) {
			Object[] fields = new Object[value.getArity()];
			for (int x = 0; x < value.getArity(); x++) {
				fields[x] = value.getField(x);
			}
			//insert values and send to cassandra
			ResultSetFuture result = session.executeAsync(preparedStatement.bind(fields));
			//add callback to detect errors
			Futures.addCallback(result, callback);
		}
	}
}
