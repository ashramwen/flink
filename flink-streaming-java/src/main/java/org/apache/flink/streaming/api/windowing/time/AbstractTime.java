/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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

package org.apache.flink.streaming.api.windowing.time;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.streaming.api.TimeCharacteristic;

import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for {@link Time} implementations.
 */
@Experimental
public abstract class AbstractTime {

	/** The time unit for this policy's time interval */
	private final TimeUnit unit;
	
	/** The size of the windows generated by this policy */
	private final long size;


	protected AbstractTime(long size, TimeUnit unit) {
		this.unit = checkNotNull(unit, "time unit may not be null");
		this.size = size;
	}

	// ------------------------------------------------------------------------
	//  Properties
	// ------------------------------------------------------------------------

	/**
	 * Gets the time unit for this policy's time interval.
	 * @return The time unit for this policy's time interval.
	 */
	public TimeUnit getUnit() {
		return unit;
	}

	/**
	 * Gets the length of this policy's time interval.
	 * @return The length of this policy's time interval.
	 */
	public long getSize() {
		return size;
	}

	/**
	 * Converts the time interval to milliseconds.
	 * @return The time interval in milliseconds.
	 */
	public long toMilliseconds() {
		return unit.toMillis(size);
	}
	
	// ------------------------------------------------------------------------
	//  Utilities
	// ------------------------------------------------------------------------

	public abstract AbstractTime makeSpecificBasedOnTimeCharacteristic(TimeCharacteristic characteristic);

	@Override
	public int hashCode() {
		return 31 * (int) (size ^ (size >>> 32)) + unit.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj != null && obj.getClass() == getClass()) {
			AbstractTime that = (AbstractTime) obj;
			return this.size == that.size && this.unit.equals(that.unit);
		}
		else {
			return false;
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + " (" + size + ' ' + unit.name() + ')';
	}
}
