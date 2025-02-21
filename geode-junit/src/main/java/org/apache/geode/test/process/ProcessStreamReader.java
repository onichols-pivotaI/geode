/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.test.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;

/**
 * Reads the output from a process stream and stores it for test validation.
 * </p>
 * Extracted from ProcessWrapper.
 */
@SuppressWarnings("unused")
public class ProcessStreamReader extends Thread {

  private volatile RuntimeException startStack;
  private volatile IOException streamClosedStack;

  private final String command;
  private final BufferedReader reader;
  private final Consumer<String> consumer;

  private int lineCount = 0;

  public ProcessStreamReader(final String command, final InputStream stream,
      final Consumer<String> consumer) {
    this.command = command;
    reader = new BufferedReader(new InputStreamReader(stream));
    this.consumer = consumer;
  }

  @Override
  public void start() {
    startStack = new RuntimeException(command);
    super.start();
  }

  @Override
  public void run() {
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        lineCount++;
        consumer.accept(line);
      }

      // EOF
      reader.close();
    } catch (IOException streamClosed) {
      streamClosedStack = streamClosed;
    }
  }

  // a test can use this to check if stream was closed cleanly or by tear-down
  public IOException getStreamClosedStack() {
    return streamClosedStack;
  }
}
