/**
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

package org.apache.flink.fs.tachyon;

import java.io.IOException;

import org.apache.flink.core.fs.FSDataInputStream;

import tachyon.client.InStream;

/**
 * Implementation of the {@link FSDataInputStream} interface for the Tachyon File System.
 */
public class TachyonDataInputStream extends FSDataInputStream {
	private final InStream inStream;

	public TachyonDataInputStream(InStream inStream) {
		this.inStream = inStream;
	}

	@Override
	public void seek(long desired) throws IOException {
		inStream.seek(desired);
	}

	@Override
	public int read() throws IOException {
		return inStream.read();
	}
	
	@Override
	public int read(byte[] b) throws IOException {
		return inStream.read(b);
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		return inStream.read(b, off, len);
	}

	@Override
	public long skip(long n) throws IOException {
		return inStream.skip(n);
	}
	
	@Override
	public void close() throws IOException {
		inStream.close();
	}
}
