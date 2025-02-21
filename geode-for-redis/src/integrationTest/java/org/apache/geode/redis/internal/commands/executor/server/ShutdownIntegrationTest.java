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
 *
 */

package org.apache.geode.redis.internal.commands.executor.server;

import static org.apache.geode.redis.internal.RedisConstants.ERROR_UNKNOWN_COMMAND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import org.apache.geode.redis.GeodeRedisServerRule;
import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.test.awaitility.GeodeAwaitility;

public class ShutdownIntegrationTest implements RedisIntegrationTest {
  protected Jedis jedis;
  private static final int REDIS_CLIENT_TIMEOUT =
      Math.toIntExact(GeodeAwaitility.getTimeout().toMillis());

  @Before
  public void setUp() {
    jedis = new Jedis("localhost", getPort(), REDIS_CLIENT_TIMEOUT);
  }

  @After
  public void classLevelTearDown() {
    jedis.close();
  }

  @Rule
  public GeodeRedisServerRule server = new GeodeRedisServerRule();

  @Override
  public int getPort() {
    return server.getPort();
  }

  @Test
  public void shutdownIsDisabled_whenOnlySupportedCommandsAreAllowed() {
    final String EXPECTED_ERROR_MSG =
        String.format("ERR " + ERROR_UNKNOWN_COMMAND, "SHUTDOWN", "");

    assertThatThrownBy(
        () -> jedis.shutdown())
            .isInstanceOf(JedisDataException.class)
            .hasMessage(EXPECTED_ERROR_MSG);
    assertThat(jedis.keys("*")).isEmpty();
  }
}
