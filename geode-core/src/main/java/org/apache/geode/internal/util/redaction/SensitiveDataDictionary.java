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
package org.apache.geode.internal.util.redaction;

/**
 * Evaluates strings to determine if they identify sensitive data.
 */
@FunctionalInterface
interface SensitiveDataDictionary {

  /**
   * Returns true if a string identifies sensitive data. For example, a string containing
   * the word "password" identifies data that is sensitive and should be secured.
   *
   * @param string The string to be evaluated.
   *
   * @return true if the string identifies sensitive data.
   */
  boolean isSensitive(String string);
}
