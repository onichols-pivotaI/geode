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
package org.apache.geode.test.junit.rules;

import static org.mockito.Mockito.spy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.rules.ExternalResource;
import org.springframework.shell.core.Completion;
import org.springframework.shell.core.Converter;

import org.apache.geode.internal.classloader.ClassPathLoader;
import org.apache.geode.management.cli.CliMetaData;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.cli.CliAroundInterceptor;
import org.apache.geode.management.internal.cli.CommandManager;
import org.apache.geode.management.internal.cli.GfshParseResult;
import org.apache.geode.management.internal.cli.GfshParser;
import org.apache.geode.management.internal.cli.remote.CommandExecutor;
import org.apache.geode.management.internal.cli.result.CommandResult;
import org.apache.geode.management.internal.cli.result.model.ResultModel;
import org.apache.geode.test.junit.assertions.CommandResultAssert;

public class GfshParserRule extends ExternalResource {

  private GfshParser parser;
  private CommandManager commandManager;
  private CommandExecutor commandExecutor;

  @Override
  public void before() {
    commandManager = new CommandManager();
    parser = new GfshParser(commandManager);
    // GfshParserRule doesn't need dlock service
    commandExecutor = new CommandExecutor(null);
  }

  public GfshParseResult parse(String command) {
    return parser.parse(command);
  }

  /**
   * @deprecated use executeAndAssertThat instead
   */
  public <T> CommandResult executeCommandWithInstance(T instance, String command) {
    GfshParseResult parseResult = parse(command);

    if (parseResult == null) {
      return new CommandResult(ResultModel.createError("Invalid command: " + command));
    }

    CliAroundInterceptor interceptor = null;
    CliMetaData cliMetaData = parseResult.getMethod().getAnnotation(CliMetaData.class);

    if (cliMetaData != null) {
      String interceptorClass = cliMetaData.interceptor();
      if (!CliMetaData.ANNOTATION_NULL_VALUE.equals(interceptorClass)) {
        try {
          interceptor = (CliAroundInterceptor) ClassPathLoader.getLatest().forName(interceptorClass)
              .newInstance();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }

        ResultModel preExecResult = interceptor.preExecution(parseResult);
        if (preExecResult instanceof ResultModel) {
          if (preExecResult.getStatus() != Result.Status.OK) {
            return new CommandResult(preExecResult);
          }
        } else {
          if (Result.Status.ERROR.equals(((Result) preExecResult).getStatus())) {
            return new CommandResult(preExecResult);
          }
        }
      }
    }

    Object exeResult = commandExecutor.execute(instance, parseResult);
    return new CommandResult((ResultModel) exeResult);
  }

  public <T> CommandResultAssert executeAndAssertThat(T instance, String command) {
    CommandResult result = executeCommandWithInstance(instance, command);
    System.out.println("Command Result:");
    System.out.println(result.asString());
    return new CommandResultAssert(result);
  }

  public CommandCandidate complete(String command) {
    List<Completion> candidates = new ArrayList<>();
    int cursor = parser.completeAdvanced(command, command.length(), candidates);
    return new CommandCandidate(command, cursor, candidates);
  }

  public <T extends Converter> T spyConverter(Class<T> klass) {
    Set<Converter<?>> converters = parser.getConverters();
    T foundConverter = null, spy = null;
    for (Converter converter : converters) {
      if (klass.isAssignableFrom(converter.getClass())) {
        foundConverter = (T) converter;
        break;
      }
    }
    if (foundConverter != null) {
      parser.remove(foundConverter);
      spy = spy(foundConverter);
      parser.add(spy);
    }
    return spy;
  }

  public GfshParser getParser() {
    return parser;
  }

  public CommandManager getCommandManager() {
    return commandManager;
  }

  public static class CommandCandidate {
    private final String command;
    private final int cursor;
    private final List<Completion> candidates;

    public CommandCandidate(String command, int cursor, List<Completion> candidates) {
      this.command = command;
      this.cursor = cursor;
      this.candidates = candidates;
    }

    public String getCandidate(int index) {
      return command.substring(0, cursor) + candidates.get(index).getValue();
    }

    public String getFirstCandidate() {
      return getCandidate(0);
    }

    public int size() {
      return candidates.size();
    }

    public int getCursor() {
      return cursor;
    }

    public List<Completion> getCandidates() {
      return candidates;
    }
  }
}
