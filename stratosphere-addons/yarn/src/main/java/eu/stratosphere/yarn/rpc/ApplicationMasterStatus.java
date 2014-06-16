/***********************************************************************************************************************
 * Copyright (C) 2010-2013 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 **********************************************************************************************************************/

package eu.stratosphere.yarn.rpc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import eu.stratosphere.core.io.IOReadableWritable;

/**
 * Class holding status information about the ApplicatioMaster.
 * The client is requesting the AM status regularly from the AM.
 */
public class ApplicationMasterStatus implements IOReadableWritable {
	private int numTaskManagers = 0;
	private int numSlots = 0;
	private int messageCount = 0;
	
	
	public ApplicationMasterStatus() {
		// for instantiation
	}
	
	public ApplicationMasterStatus(int numTaskManagers, int numSlots,
			int messageCount) {
		this.numTaskManagers = numTaskManagers;
		this.numSlots = numSlots;
		this.messageCount = messageCount;
	}

	public int getNumberOfTaskManagers() {
		return numTaskManagers;
	}

	public int getNumberOfAvailableSlots() {
		return numSlots;
	}
	
	public int getMessageCount() {
		return messageCount;
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(numTaskManagers);
		out.writeInt(numSlots);
		out.writeInt(messageCount);
	}

	@Override
	public void read(DataInput in) throws IOException {
		numTaskManagers = in.readInt();
		numSlots = in.readInt();
		messageCount = in.readInt();
	}
}
