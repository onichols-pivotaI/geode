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

import static java.lang.String.format;

import java.io.ObjectStreamException;

import org.apache.geode.annotations.Immutable;


/**
 * Enumerated type for region data policy. The data policy specifies how this local cache will
 * handle the data for a region.
 * <ol>
 * <li><code>EMPTY</code> causes data to never be stored in local memory. The region will always
 * appear empty. It can be used to for zero footprint producers that only want to distribute their
 * data to others and for zero footprint consumers that only want to see events.
 * <li><code>NORMAL</code> causes data that this region is interested in to be stored in local
 * memory. It allows the contents in this cache to differ from other caches.
 * <li><code>PARTITION</code> causes data that this region holds to be spread across processes. The
 * amount of data held in this cache is configured in {@link PartitionAttributes} with a
 * {@link PartitionAttributesFactory}.
 * <li><code>PERSISTENT_PARTITION</code> in addition to <code>PARTITION</code> also causes data to
 * be stored to disk. The region initialization uses the data stored on disk.
 * <li><code>REPLICATE</code> causes data that this region is interested in to be stored in local
 * memory. A distributed region will be initialized with the data from other caches. On distributed
 * region operations that would cause the contents to differ with other caches are not allowed. This
 * policy is allowed on local scope region but it behaves the same as <code>NORMAL</code>.
 * <li><code>PERSISTENT_REPLICATE</code> in addition to <code>REPLICATE</code> also causes data to
 * be stored to disk. The region initialization uses the data stored on disk. Note that the
 * persistence applies to both local scope and distributed scope.
 * </ol>
 *
 *
 *
 * @see AttributesFactory#setDataPolicy
 * @see RegionAttributes#getDataPolicy
 *
 * @since GemFire 5.0
 */
@Immutable
public class DataPolicy implements java.io.Serializable {
  private static final long serialVersionUID = 2095573273889467233L;

  @Immutable
  private static final DataPolicy[] VALUES = new DataPolicy[11];

  @Immutable
  private static final String[] NAMES = new String[VALUES.length];

  /**
   * Data is never stored in local memory. The region will always be empty locally. It can be used
   * to for zero footprint producers that only want to distribute their data to others and for zero
   * footprint consumers that only want to see events.
   */
  @Immutable
  public static final DataPolicy EMPTY = new DataPolicy(0, "EMPTY");

  /**
   * Allows the contents in this cache to differ from other caches.
   * <p>
   * Data that this region is interested in is stored in local memory.
   */
  @Immutable
  public static final DataPolicy NORMAL = new DataPolicy(1, "NORMAL");

  /**
   * The region will be initialized with the data from other caches and accepts any new entries
   * created in other caches.
   * <p>
   * Operations that would cause the contents to differ with other caches are not allowed.
   * <p>
   * Data that this region is interested in is stored in local memory.
   */
  @Immutable
  public static final DataPolicy REPLICATE = new DataPolicy(2, "REPLICATE");

  /**
   * In addition to <code>REPLICATE</code> also causes data to be stored to disk. The region
   * initialization may use the data stored on disk.
   */
  @Immutable
  public static final DataPolicy PERSISTENT_REPLICATE = new DataPolicy(3, "PERSISTENT_REPLICATE");


  /**
   * Data in this region may be spread across a number of processes. This is further configured with
   * {@link PartitionAttributes partitioning attributes}
   */
  @Immutable
  public static final DataPolicy PARTITION = new DataPolicy(4, "PARTITION");

  /**
   * In addition to <code>NORMAL</code>, contents inside of this cache are (partially) initialized
   * with data from other caches, if available.
   */
  @Immutable
  public static final DataPolicy PRELOADED = new DataPolicy(5, "PRELOADED");

  /**
   * In addition to <code>PARTITION</code> also causes data to be stored to disk. The region
   * initialization may use the data stored on disk.
   *
   * @since GemFire 6.5
   */
  @Immutable
  public static final DataPolicy PERSISTENT_PARTITION = new DataPolicy(6, "PERSISTENT_PARTITION");

  /**
   * The data policy used by default; it is {@link #NORMAL}.
   */
  @Immutable
  public static final DataPolicy DEFAULT = NORMAL;


  /** The name of this mirror type. */
  private final transient String name;

  /**
   * Used as ordinal to represent this DataPolicy
   *
   * @deprecated use {@link #ordinal()}
   */
  @Deprecated
  public final byte ordinal;

  private Object readResolve() throws ObjectStreamException {
    return VALUES[ordinal]; // Canonicalize
  }


  /** Creates a new instance of DataPolicy. */
  private DataPolicy(final int ordinal, final String name) {
    if (ordinal >= VALUES.length) {
      throw new IllegalArgumentException(
          format("Only %s DataPolicies may be defined", VALUES.length + 1));
    }
    if (VALUES[ordinal] != null) {
      throw new IllegalArgumentException(
          format("Ordinal %s is already defined by %s", ordinal, VALUES[ordinal]));
    }
    this.name = name;
    this.ordinal = (byte) (ordinal & 0xff);
    VALUES[this.ordinal] = this;
    NAMES[this.ordinal] = name;
  }

  /**
   * @return ordinal value.
   */
  public int ordinal() {
    return ordinal;
  }

  /**
   * Get enum value by name.
   *
   * @param name of enum value.
   * @return enum by name.
   * @throws IllegalArgumentException if the specified enum type has no constant with the specified
   *         name.
   * @throws NullPointerException if name is null.
   */
  public static DataPolicy valueOf(final String name) throws IllegalArgumentException {
    if (null == name) {
      throw new NullPointerException();
    }

    for (int i = 0; i < NAMES.length; i++) {
      if (NAMES[i].equals(name)) {
        return VALUES[i];
      }
    }

    throw new IllegalArgumentException(name);
  }

  /** Return the DataPolicy represented by specified ordinal */
  public static DataPolicy fromOrdinal(byte ordinal) {
    return VALUES[ordinal];
  }


  /**
   * Return true if regions with this policy store data locally.
   * <p>
   * Although DataPolicy {@link #PARTITION} will return true to this query, it is possible to turn
   * off local storage with {@link PartitionAttributesFactory#setLocalMaxMemory(int)} by setting
   * localMaxMemory to zero.
   *
   * @return true if regions with this policy store data locally.
   * @see #NORMAL
   * @see #PRELOADED
   * @see #REPLICATE
   * @see #PERSISTENT_REPLICATE
   * @see #PARTITION
   * @see #PERSISTENT_PARTITION
   */
  public boolean withStorage() {
    return this != EMPTY;
  }

  /**
   * Return whether this policy does replication.
   *
   * @return true if this policy does replication.
   * @see #REPLICATE
   * @see #PERSISTENT_REPLICATE
   */
  public boolean withReplication() {
    return this == REPLICATE || this == PERSISTENT_REPLICATE;
  }

  /**
   * Return whether this policy does persistence.
   *
   * @return true if this policy does persistence.
   * @see #PERSISTENT_PARTITION
   * @see #PERSISTENT_REPLICATE
   * @since GemFire 6.5
   */
  public boolean withPersistence() {
    return this == PERSISTENT_PARTITION || this == PERSISTENT_REPLICATE;
  }

  /**
   * Return whether this policy does partitioning.
   *
   * @return true if this policy does partitioning
   * @see #PARTITION
   * @see #PERSISTENT_PARTITION
   * @since GemFire 6.5
   */
  public boolean withPartitioning() {
    return this == PARTITION || this == PERSISTENT_PARTITION;
  }

  /**
   * Return whether this policy does preloaded.
   *
   * @return true if this policy does preloaded.
   * @see #PRELOADED
   * @since GemFire 6.5
   */
  public boolean withPreloaded() {
    return this == PRELOADED;
  }

  /**
   * Return true if this policy is {@link #EMPTY}.
   *
   * @return true if this policy is {@link #EMPTY}.
   * @deprecated from version 6.5 forward please use withStorage()
   */
  public boolean isEmpty() {
    return this == EMPTY;
  }

  /**
   * Return true if this policy is {@link #NORMAL}.
   *
   * @return true if this policy is {@link #NORMAL}.
   * @deprecated from version 6.5 forward please use an identity comparison instead of this method
   */
  public boolean isNormal() {
    return this == NORMAL;
  }

  /**
   * Return true if this policy is {@link #PRELOADED}.
   *
   * @return true if this policy is {@link #PRELOADED}
   * @deprecated from version 6.5 forward please use withPreloaded()
   */
  public boolean isPreloaded() {
    return this == PRELOADED;
  }

  /**
   * Return true if this policy is the default.
   *
   * @return true if this policy is the default.
   * @deprecated from version 6.5 forward please use an identity comparison instead of this method
   */
  public boolean isDefault() {
    return this == DEFAULT;
  }

  /**
   * Return true if this policy is {@link #REPLICATE}.
   *
   * @return true if this policy is {@link #REPLICATE}.
   * @deprecated from version 6.5 forward please use withReplication()
   */
  public boolean isReplicate() {
    return this == REPLICATE;
  }

  /**
   * Return true if this policy is {@link #PERSISTENT_REPLICATE}.
   *
   * @return true if this policy is {@link #PERSISTENT_REPLICATE}.
   * @deprecated from version 6.5 forward please use withPersistence() and withReplication()
   */
  public boolean isPersistentReplicate() {
    return this == PERSISTENT_REPLICATE;
  }

  /**
   * Return true if this policy is {@link #PARTITION}.
   *
   * @return true if this policy is {@link #PARTITION}
   * @deprecated from version 6.5 forward please use withPartitioning()
   */
  public boolean isPartition() {
    return this == PARTITION;
  }

  /**
   * Returns a string representation for this data policy.
   *
   * @return the name of this data policy.
   */
  @Override
  public String toString() {
    return name;
  }

  /**
   * @deprecated use {@link #valueOf(String)}
   */
  @Deprecated
  public static DataPolicy fromString(final String s) {
    try {
      return valueOf(s);
    } catch (NullPointerException | IllegalArgumentException e) {
      return null;
    }
  }
}
