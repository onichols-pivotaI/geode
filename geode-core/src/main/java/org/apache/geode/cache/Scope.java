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


package org.apache.geode.cache;

import java.io.ObjectStreamException;
import java.io.Serializable;

import org.apache.geode.annotations.Immutable;


/**
 * Enumerated type for region distribution scope.
 *
 *
 *
 * @see RegionAttributes#getScope
 * @see AttributesFactory#setScope
 * @since GemFire 3.0
 */
@Immutable
public class Scope implements Serializable {
  private static final long serialVersionUID = 5534399159504301602L;

  /**
   * The region with this attribute is scoped to this JVM only. Operations and data are not
   * distributed to other caches.
   */
  @Immutable
  public static final Scope LOCAL = new Scope("LOCAL", 0);

  /**
   * The region or cached object with this attribute is scoped to the distributed cached system; any
   * distributed operation will return without waiting for the remote acknowledgment.
   */
  @Immutable
  public static final Scope DISTRIBUTED_NO_ACK = new Scope("DISTRIBUTED_NO_ACK", 1);

  /**
   * The region or cached object with this attribute is scoped to the distributed cached system; any
   * distributed operation will not return until all the remote acknowledgments come back.
   */
  @Immutable
  public static final Scope DISTRIBUTED_ACK = new Scope("DISTRIBUTED_ACK", 2);

  /**
   * The region or cached object with this attribute is scoped to the distributed cached system;
   * locking is used for all distributed operations on entries to guarantee consistency across the
   * distributed caches.
   */
  @Immutable
  public static final Scope GLOBAL = new Scope("GLOBAL", 3);

  /** The name of this scope. */
  private final transient String name;

  public final int ordinal;

  @Immutable
  private static final Scope[] VALUES = {LOCAL, DISTRIBUTED_NO_ACK, DISTRIBUTED_ACK, GLOBAL};

  /*
   * The following 4 definitions are included for use by the C interface. Any changes to the order
   * of the scopes in VALUES must be reflected here and changed in gemfire.h.
   */
  static final int SCOPE_LOCAL = 0;
  static final int SCOPE_DISTRIBUTED_NO_ACK = 1;
  static final int SCOPE_DISTRIBUTED_ACK = 2;
  static final int SCOPE_GLOBAL = 3;

  private Object readResolve() throws ObjectStreamException {
    return fromOrdinal(ordinal); // Canonicalize
  }


  /** Creates a new instance of Scope. */
  private Scope(String name, int ordinal) {
    this.name = name;
    this.ordinal = ordinal;
  }

  /** Return the Scope represented by specified ordinal */
  public static Scope fromOrdinal(int ordinal) {
    return VALUES[ordinal];
  }

  /**
   * Returns whether this is local scope.
   *
   * @return true if this is LOCAL
   */
  public boolean isLocal() {
    return this == LOCAL;
  }

  /**
   * Returns whether this is one of the distributed scopes.
   *
   * @return true if this is any scope other than LOCAL
   */
  public boolean isDistributed() {
    return this != LOCAL;
  }

  /**
   * Returns whether this is distributed no ack scope.
   *
   * @return true if this is DISTRIBUTED_NO_ACK
   */
  public boolean isDistributedNoAck() {
    return this == DISTRIBUTED_NO_ACK;
  }

  /**
   * Returns whether this is distributed ack scope.
   *
   * @return true if this is DISTRIBUTED_ACK
   */
  public boolean isDistributedAck() {
    return this == DISTRIBUTED_ACK;
  }

  /**
   * Returns whether this is global scope.
   *
   * @return true if this is GLOBAL
   */
  public boolean isGlobal() {
    return this == GLOBAL;
  }

  /**
   * Returns whether acknowledgements are required for this scope.
   *
   * @return true if this is DISTRIBUTED_ACK or GLOBAL, false otherwise
   */
  public boolean isAck() {
    return this == DISTRIBUTED_ACK || this == GLOBAL;
  }


  /**
   * Returns a string representation for this scope.
   *
   * @return String the name of this scope
   */
  @Override
  public String toString() {
    return name;
  }

  public String toConfigTypeString() {
    return name.toLowerCase().replace("_", "-");
  }

  /**
   * Parse the given string into a Scope
   *
   * @param scope the provided String form of Scope
   * @return the canonical Scope associated with the string
   */
  public static Scope fromString(String scope) {
    for (final Scope value : VALUES) {
      if (value.toString().equals(scope)) {
        return value;
      }
    }
    throw new IllegalArgumentException(
        String.format("%s is not a valid string representation of %s.",
            scope, Scope.class.getName()));
  }

}
