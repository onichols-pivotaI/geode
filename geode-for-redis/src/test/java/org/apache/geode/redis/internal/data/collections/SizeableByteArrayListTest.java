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
package org.apache.geode.redis.internal.data.collections;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import org.apache.geode.cache.util.ObjectSizer;
import org.apache.geode.internal.size.ReflectionObjectSizer;

public class SizeableByteArrayListTest {
  private final ObjectSizer sizer = ReflectionObjectSizer.getInstance();

  @Test
  public void getSizeInBytesIsAccurate_ForEmptySizeableByteArrayList() {
    SizeableByteArrayList list = new SizeableByteArrayList();
    assertThat(list.getSizeInBytes()).isEqualTo(sizer.sizeof(list));
  }

  @Test
  public void getSizeInBytesIsAccurate_ForSizeableByteArrayListElements() {
    int initialNumberOfElements = 20;
    int elementsToAdd = 100;

    // Create a list with an initial size and confirm that it correctly reports its size
    SizeableByteArrayList list = new SizeableByteArrayList();
    for (int i = 0; i < initialNumberOfElements; ++i) {
      list.addFirst(makeByteArrayOfSpecifiedLength(i + 1));
    }
    assertThat(list.getSizeInBytes()).isEqualTo(sizer.sizeof(list));

    // Add elements and assert that the size is correct after each add
    for (int i = initialNumberOfElements; i < initialNumberOfElements + elementsToAdd; ++i) {
      list.addFirst(makeByteArrayOfSpecifiedLength(i));
      assertThat(list.getSizeInBytes()).isEqualTo(sizer.sizeof(list));
    }
    assertThat(list.size()).isEqualTo(initialNumberOfElements + elementsToAdd);

    // Remove all the elements and assert that the size is correct after each remove
    for (int i = 0; i < initialNumberOfElements + elementsToAdd; ++i) {
      list.remove(0);
      assertThat(list.getSizeInBytes()).isEqualTo(sizer.sizeof(list));
    }
    assertThat(list.size()).isEqualTo(0);
  }

  byte[] makeByteArrayOfSpecifiedLength(int length) {
    byte[] newByteArray = new byte[length];
    for (int i = 0; i < length; i++) {
      newByteArray[i] = (byte) i;
    }
    return newByteArray;
  }

}
