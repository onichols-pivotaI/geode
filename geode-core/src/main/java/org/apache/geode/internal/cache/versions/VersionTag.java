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
package org.apache.geode.internal.cache.versions;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.apache.logging.log4j.Logger;

import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.persistence.DiskStoreID;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.size.ReflectionSingleObjectSizer;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * VersionTags are sent with distribution messages and carry version info for the operation.
 * <p/>
 * Note that on the receiving end the membership IDs in a version tag will not be references to
 * canonical IDs and should be made so before storing them for any length of time.
 * <p/>
 * This class implements java.io.Serializable for dunit testing. It should not otherwise be
 * serialized with that mechanism.
 *
 */
public abstract class VersionTag<T extends VersionSource>
    implements DataSerializableFixedID, java.io.Serializable, VersionHolder<T> {
  private static final Logger logger = LogService.getLogger();

  private static final long serialVersionUID = 9098338414308465271L;

  // tag_size represents the tag, but does not count member ID sizes since they are
  // interned in the region version vectors
  public static final int TAG_SIZE =
      ReflectionSingleObjectSizer.OBJECT_SIZE + ReflectionSingleObjectSizer.REFERENCE_SIZE * 2 + 23;

  /**
   * A timestamp that cannot exist due to range restrictions. This is used to mark a timestamp as
   * not being real
   */
  public static final long ILLEGAL_VERSION_TIMESTAMP = 0x8000000000000000l;


  // flags for serialization
  static final int HAS_MEMBER_ID = 0x01;
  static final int HAS_PREVIOUS_MEMBER_ID = 0x02;
  static final int VERSION_TWO_BYTES = 0x04;
  static final int DUPLICATE_MEMBER_IDS = 0x08;
  static final int HAS_RVV_HIGH_BYTE = 0x10;

  private static final int BITS_POSDUP = 0x01;
  private static final int BITS_RECORDED = 0x02; // has the rvv recorded this?
  private static final int BITS_HAS_PREVIOUS_ID = 0x04;
  private static final int BITS_GATEWAY_TAG = 0x08;
  private static final int BITS_IS_REMOTE_TAG = 0x10;
  private static final int BITS_TIMESTAMP_APPLIED = 0x20;

  private static final int BITS_ALLOWED_BY_RESOLVER = 0x40;
  // Note: the only valid BITS_* are 0xFFFF.

  /**
   * the per-entry version number for the operation
   */
  private int entryVersion;

  /**
   * high byte for large region version numbers
   */
  private short regionVersionHighBytes;

  /**
   * low bytes for region version numbers
   */
  private int regionVersionLowBytes;

  /**
   * time stamp
   */
  private long timeStamp;

  /**
   * distributed system ID
   */
  private byte distributedSystemId;

  // In GEODE-1252 we found that the bits field
  // was concurrently modified by calls to
  // setPreviousMemberID and setRecorded.
  // So bits has been changed to volatile and
  // all modification to it happens using AtomicIntegerFieldUpdater.
  private static final AtomicIntegerFieldUpdater<VersionTag> bitsUpdater =
      AtomicIntegerFieldUpdater.newUpdater(VersionTag.class, "bits");
  /**
   * boolean bits Note: this is an int field so it has 32 bits BUT only the lower 16 bits are
   * serialized. So all our code should treat this an an unsigned short field.
   */
  private volatile int bits;

  /**
   * the initiator of the operation. If null, the initiator was the sender of the operation
   */
  private T memberID;

  /**
   * for Delta operations, the ID of the version stamp on which the delta is based. The version
   * number for that stamp is getEntryVersion()-1
   */
  private T previousMemberID;

  public boolean isFromOtherMember() {
    return (bits & BITS_IS_REMOTE_TAG) != 0;
  }

  /** was the timestamp of this tag used to update the cache's timestamp? */
  public boolean isTimeStampUpdated() {
    return (bits & BITS_TIMESTAMP_APPLIED) != 0;
  }

  /** record that the timestamp from this tag was applied to the cache */
  public void setTimeStampApplied(boolean isTimeStampUpdated) {
    if (isTimeStampUpdated) {
      setBits(BITS_TIMESTAMP_APPLIED);
    } else {
      clearBits(~BITS_TIMESTAMP_APPLIED);
    }
  }

  /**
   * @return true if this is a gateway timestamp holder rather than a full version tag
   */
  public boolean isGatewayTag() {
    return (bits & BITS_GATEWAY_TAG) != 0;
  }

  public void setEntryVersion(int version) {
    entryVersion = version;
  }

  @Override
  public int getEntryVersion() {
    return entryVersion;
  }

  public void setVersionTimeStamp(long timems) {
    timeStamp = timems;
  }

  public void setIsGatewayTag(boolean isGateway) {
    if (isGateway) {
      setBits(BITS_GATEWAY_TAG);
    } else {
      clearBits(~BITS_GATEWAY_TAG);
    }
  }

  public void setRegionVersion(long version) {
    regionVersionHighBytes = (short) ((version & 0xFFFF00000000L) >> 32);
    regionVersionLowBytes = (int) (version & 0xFFFFFFFFL);
  }

  @Override
  public long getRegionVersion() {
    return (((long) regionVersionHighBytes) << 32) | (regionVersionLowBytes & 0x00000000FFFFFFFFL);
  }

  /**
   * set rvv internal bytes. Used by region entries
   */
  public void setRegionVersion(short highBytes, int lowBytes) {
    regionVersionHighBytes = highBytes;
    regionVersionLowBytes = lowBytes;
  }

  /**
   * get rvv internal high byte. Used by region entries for transferring to storage
   */
  @Override
  public short getRegionVersionHighBytes() {
    return regionVersionHighBytes;
  }

  /**
   * get rvv internal low bytes. Used by region entries for transferring to storage
   */
  @Override
  public int getRegionVersionLowBytes() {
    return regionVersionLowBytes;
  }

  /**
   * set that this tag has been recorded in a receiver's RVV
   */
  public void setRecorded() {
    setBits(BITS_RECORDED);
  }

  /**
   * has this tag been recorded in a receiver's RVV?
   */
  public boolean isRecorded() {
    return ((bits & BITS_RECORDED) != 0);
  }

  /**
   * Set canonical ID objects into this version tag using the DM's cache of IDs
   *
   */
  public void setCanonicalIDs(DistributionManager distributionManager) {}

  /**
   * @return the memberID
   */
  @Override
  public T getMemberID() {
    return memberID;
  }

  /**
   * @param memberID the memberID to set
   */
  public void setMemberID(T memberID) {
    this.memberID = memberID;
  }

  /**
   * @return the previousMemberID
   */
  public T getPreviousMemberID() {
    return previousMemberID;
  }

  /**
   * @param previousMemberID the previousMemberID to set
   */
  public void setPreviousMemberID(T previousMemberID) {
    setBits(BITS_HAS_PREVIOUS_ID);
    this.previousMemberID = previousMemberID;
  }

  /**
   * sets the possible-duplicate flag for this tag. When a tag has this bit it means that the cache
   * had seen the operation that was being applied to it and plucked out the current version stamp
   * to use in propagating the event to other members and clients. A member receiving this event
   * should not allow duplicate application of the event to the cache.
   */
  public VersionTag setPosDup(boolean flag) {
    if (flag) {
      setBits(BITS_POSDUP);
    } else {
      clearBits(~BITS_POSDUP);
    }
    return this;
  }

  public boolean isPosDup() {
    return (bits & BITS_POSDUP) != 0;
  }

  /**
   * set or clear the flag that this tag was blessed by a conflict resolver
   *
   * @return this tag
   */
  public VersionTag setAllowedByResolver(boolean flag) {
    if (flag) {
      setBits(BITS_ALLOWED_BY_RESOLVER);
    } else {
      clearBits(~BITS_ALLOWED_BY_RESOLVER);
    }
    return this;
  }

  public boolean isAllowedByResolver() {
    return (bits & BITS_ALLOWED_BY_RESOLVER) != 0;
  }

  @Override
  public int getDistributedSystemId() {
    return distributedSystemId;
  }

  public void setDistributedSystemId(int id) {
    distributedSystemId = (byte) (id & 0xFF);
  }

  /**
   * replace null member IDs with the given identifier. This is used to incorporate version
   * information into the cache that has been received from another VM
   *
   */
  public void replaceNullIDs(VersionSource id) {
    if (memberID == null) {
      memberID = (T) id;
    }
    if (previousMemberID == null && hasPreviousMemberID() && entryVersion > 1) {
      previousMemberID = (T) id;
    }
  }

  /**
   * returns true if this tag has a previous member ID for delta operation checks
   */
  public boolean hasPreviousMemberID() {
    return (bits & BITS_HAS_PREVIOUS_ID) != 0;
  }

  /**
   * returns true if entry and region version numbers are not both zero, meaning this has valid
   * version numbers
   */
  public boolean hasValidVersion() {
    return !(entryVersion == 0 && regionVersionHighBytes == 0
        && regionVersionLowBytes == 0);
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    toData(out, true);
  }

  public void toData(DataOutput out, boolean includeMember) throws IOException {
    int flags = 0;
    boolean versionIsShort = false;
    if (entryVersion < 0x10000) {
      versionIsShort = true;
      flags |= VERSION_TWO_BYTES;
    }
    if (regionVersionHighBytes != 0) {
      flags |= HAS_RVV_HIGH_BYTE;
    }
    if (memberID != null && includeMember) {
      flags |= HAS_MEMBER_ID;
    }
    boolean writePreviousMemberID = false;
    if (previousMemberID != null && includeMember) {
      flags |= HAS_PREVIOUS_MEMBER_ID;
      if (Objects.equals(previousMemberID, memberID)) {
        flags |= DUPLICATE_MEMBER_IDS;
      } else {
        writePreviousMemberID = true;
      }
    }
    if (logger.isTraceEnabled(LogMarker.VERSION_TAG_VERBOSE)) {
      logger.trace(LogMarker.VERSION_TAG_VERBOSE, "serializing {} with flags 0x{}", getClass(),
          Integer.toHexString(flags));
    }
    out.writeShort(flags);
    out.writeShort(bits);
    out.write(distributedSystemId);
    if (versionIsShort) {
      out.writeShort(entryVersion & 0xffff);
    } else {
      out.writeInt(entryVersion);
    }
    if (regionVersionHighBytes != 0) {
      out.writeShort(regionVersionHighBytes);
    }
    out.writeInt(regionVersionLowBytes);
    InternalDataSerializer.writeUnsignedVL(timeStamp, out);
    if (memberID != null && includeMember) {
      writeMember(memberID, out);
    }
    if (writePreviousMemberID) {
      writeMember(previousMemberID, out);
    }
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    int flags = in.readUnsignedShort();
    if (logger.isTraceEnabled(LogMarker.VERSION_TAG_VERBOSE)) {
      logger.trace(LogMarker.VERSION_TAG_VERBOSE, "deserializing {} with flags 0x{}",
          getClass(), Integer.toHexString(flags));
    }
    bitsUpdater.set(this, in.readUnsignedShort());
    distributedSystemId = in.readByte();
    if ((flags & VERSION_TWO_BYTES) != 0) {
      entryVersion = in.readShort() & 0xffff;
    } else {
      entryVersion = in.readInt() & 0xffffffff;
    }
    if ((flags & HAS_RVV_HIGH_BYTE) != 0) {
      regionVersionHighBytes = in.readShort();
    }
    regionVersionLowBytes = in.readInt();
    timeStamp = InternalDataSerializer.readUnsignedVL(in);
    if ((flags & HAS_MEMBER_ID) != 0) {
      memberID = readMember(in);
    }
    if ((flags & HAS_PREVIOUS_MEMBER_ID) != 0) {
      if ((flags & DUPLICATE_MEMBER_IDS) != 0) {
        previousMemberID = memberID;
      } else {
        try {
          previousMemberID = readMember(in);
        } catch (BufferUnderflowException e) {
          if (context.getSerializationVersion().isOlderThan(KnownVersion.GEODE_1_11_0)) {
            // GEODE-7219: older versions may report HAS_PREVIOUS_MEMBER_ID but not transmit it
            logger.info("Buffer underflow encountered while reading a version tag - ignoring");
          } else {
            throw e;
          }
        }
      }
    }
    setBits(BITS_IS_REMOTE_TAG);
  }

  /** for unit testing receipt of version tags from another member of the cluster */
  public void setIsRemoteForTesting() {
    setBits(BITS_IS_REMOTE_TAG);
  }

  public abstract T readMember(DataInput in) throws IOException, ClassNotFoundException;

  public abstract void writeMember(T memberID, DataOutput out) throws IOException;


  @Override
  public String toString() {
    StringBuilder s = new StringBuilder();
    if (isGatewayTag()) {
      s.append("{ds=").append(distributedSystemId).append("; time=")
          .append(getVersionTimeStamp()).append("}");
    } else {
      s.append("{v").append(entryVersion);
      s.append("; rv").append(getRegionVersion());
      if (memberID != null) {
        s.append("; mbr=").append(memberID);
      }
      if (hasPreviousMemberID()) {
        s.append("; prev=").append(previousMemberID);
      }
      if (distributedSystemId >= 0) {
        s.append("; ds=").append(distributedSystemId);
      }
      s.append("; time=").append(getVersionTimeStamp());
      if (isFromOtherMember()) {
        s.append("; remote");
      }
      if (isAllowedByResolver()) {
        s.append("; allowed");
      }
      s.append("}");
    }
    return s.toString();
  }


  /**
   * @return the time stamp of this operation. This is an unsigned integer returned as a long
   */
  @Override
  public long getVersionTimeStamp() {
    return timeStamp;
  }

  /**
   * Creates a version tag of the appropriate type, based on the member id
   *
   */
  public static VersionTag create(VersionSource memberId) {
    VersionTag tag;
    if (memberId instanceof DiskStoreID) {
      tag = new DiskVersionTag();
    } else {
      tag = new VMVersionTag();
    }

    tag.setMemberID(memberId);

    return tag;
  }

  public static VersionTag create(boolean persistent, DataInput in)
      throws IOException, ClassNotFoundException {
    VersionTag<?> tag;
    if (persistent) {
      tag = new DiskVersionTag();
    } else {
      tag = new VMVersionTag();
    }
    InternalDataSerializer.invokeFromData(tag, in);
    return tag;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + entryVersion;
    result = prime * result + ((memberID == null) ? 0 : memberID.hashCode());
    result = prime * result + regionVersionHighBytes;
    result = prime * result + regionVersionLowBytes;
    if (isGatewayTag()) {
      result = prime * result + (int) timeStamp;
      result = prime * result + (int) (timeStamp >>> 32);
    }
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    VersionTag<?> other = (VersionTag<?>) obj;
    if (entryVersion != other.entryVersion) {
      return false;
    }
    if (memberID == null) {
      if (other.memberID != null) {
        return false;
      }
    } else if (!memberID.equals(other.memberID)) {
      return false;
    }
    if (regionVersionHighBytes != other.regionVersionHighBytes) {
      return false;
    }
    if (regionVersionLowBytes != other.regionVersionLowBytes) {
      return false;
    }
    if (isGatewayTag() != other.isGatewayTag()) {
      return false;
    }
    if (isGatewayTag()) {
      if (timeStamp != other.timeStamp) {
        return false;
      }
      return distributedSystemId == other.distributedSystemId;
    }
    return true;
  }

  /**
   * Set any bits in the given bitMask on the bits field
   */
  private void setBits(int bitMask) {
    int oldBits;
    int newBits;
    do {
      oldBits = bits;
      newBits = oldBits | bitMask;
    } while (!bitsUpdater.compareAndSet(this, oldBits, newBits));
  }

  /**
   * Clear any bits not in the given bitMask from the bits field
   */
  private void clearBits(int bitMask) {
    int oldBits;
    int newBits;
    do {
      oldBits = bits;
      newBits = oldBits & bitMask;
    } while (!bitsUpdater.compareAndSet(this, oldBits, newBits));
  }
}
