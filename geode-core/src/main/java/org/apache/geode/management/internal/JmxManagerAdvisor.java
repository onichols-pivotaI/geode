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
package org.apache.geode.management.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.geode.CancelException;
import org.apache.geode.DataSerializer;
import org.apache.geode.SystemFailure;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DistributionAdvisee;
import org.apache.geode.distributed.internal.DistributionAdvisor;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.HighPriorityDistributionMessage;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * @since GemFire 7.0
 */
public class JmxManagerAdvisor extends DistributionAdvisor {

  private static final Logger logger = LogService.getLogger();

  private JmxManagerAdvisor(DistributionAdvisee advisee) {
    super(advisee);
    JmxManagerProfile p =
        new JmxManagerProfile(getDistributionManager().getId(), incrementAndGetVersion());
    advisee.fillInProfile(p);
    ((JmxManagerAdvisee) advisee).initProfile(p);
  }

  public static JmxManagerAdvisor createJmxManagerAdvisor(DistributionAdvisee advisee) {
    JmxManagerAdvisor advisor = new JmxManagerAdvisor(advisee);
    advisor.initialize();
    return advisor;
  }

  @Override
  public String toString() {
    return "JmxManagerAdvisor for " + getAdvisee();
  }

  public void broadcastChange() {
    try {
      Set<InternalDistributedMember> recips = adviseGeneric(); // for now just tell everyone
      JmxManagerProfile p =
          new JmxManagerProfile(getDistributionManager().getId(), incrementAndGetVersion());
      getAdvisee().fillInProfile(p);
      JmxManagerProfileMessage.send(getAdvisee().getSystem().getDistributionManager(), recips, p);
    } catch (CancelException ignore) {
    }
  }

  @SuppressWarnings("unchecked")
  public List<JmxManagerProfile> adviseAlreadyManaging() {
    return fetchProfiles(profile -> {
      assert profile instanceof JmxManagerProfile;
      JmxManagerProfile jmxProfile = (JmxManagerProfile) profile;
      return jmxProfile.isJmxManagerRunning();
    });
  }

  @SuppressWarnings("unchecked")
  public List<JmxManagerProfile> adviseWillingToManage() {
    return fetchProfiles(profile -> {
      assert profile instanceof JmxManagerProfile;
      JmxManagerProfile jmxProfile = (JmxManagerProfile) profile;
      return jmxProfile.isJmxManager();
    });
  }

  @Override
  protected Profile instantiateProfile(InternalDistributedMember memberId, int version) {
    return new JmxManagerProfile(memberId, version);
  }

  /**
   * Overridden to also include our profile. If our profile is included it will always be first.
   */
  @Override
  protected List/* <Profile> */ fetchProfiles(Filter f) {
    initializationGate();
    List result = null;
    {
      JmxManagerAdvisee advisee = (JmxManagerAdvisee) getAdvisee();
      Profile myp = advisee.getMyMostRecentProfile();
      if (f == null || f.include(myp)) {
        if (result == null) {
          result = new ArrayList();
        }
        result.add(myp);
      }
    }
    Profile[] locProfiles = profiles; // grab current profiles
    for (Profile profile : locProfiles) {
      if (f == null || f.include(profile)) {
        if (result == null) {
          result = new ArrayList(locProfiles.length);
        }
        result.add(profile);
      }
    }
    if (result == null) {
      result = Collections.EMPTY_LIST;
    } else {
      result = Collections.unmodifiableList(result);
    }
    return result;
  }

  /**
   * Message used to push event updates to remote VMs
   */
  public static class JmxManagerProfileMessage extends HighPriorityDistributionMessage {
    private volatile JmxManagerProfile profile;
    private volatile int processorId;

    /**
     * Default constructor used for de-serialization (used during receipt)
     */
    public JmxManagerProfileMessage() {}

    @Override
    public boolean sendViaUDP() {
      return true;
    }

    /**
     * Constructor used to send
     *
     */
    private JmxManagerProfileMessage(final Set<InternalDistributedMember> recips,
        final JmxManagerProfile p) {
      setRecipients(recips);
      processorId = 0;
      profile = p;
    }

    @Override
    protected void process(ClusterDistributionManager dm) {
      Throwable thr = null;
      JmxManagerProfile p = null;
      try {
        final InternalCache cache = dm.getCache();
        if (cache != null && !cache.isClosed()) {
          final JmxManagerAdvisor adv = cache.getJmxManagerAdvisor();
          p = profile;
          if (p != null) {
            adv.putProfile(p);
          }
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug("No cache {}", this);
          }
        }
      } catch (CancelException e) {
        if (logger.isDebugEnabled()) {
          logger.debug("Cache closed, {}", this);
        }
      } catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable t) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (thr != null) {
          dm.getCancelCriterion().checkCancelInProgress(null);
          logger.info(String.format("This member caught exception processing profile %s %s",
              p, this), thr);
        }
      }
    }

    @Override
    public int getDSFID() {
      return JMX_MANAGER_PROFILE_MESSAGE;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      processorId = in.readInt();
      profile = DataSerializer.readObject(in);
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      out.writeInt(processorId);
      DataSerializer.writeObject(profile, out);
    }

    /**
     * Send profile to the provided members
     *
     * @param recips The recipients of the message
     */
    public static void send(final DistributionManager dm, Set<InternalDistributedMember> recips,
        JmxManagerProfile profile) {
      JmxManagerProfileMessage r = new JmxManagerProfileMessage(recips, profile);
      dm.putOutgoing(r);
    }

    @Override
    public String getShortClassName() {
      return "JmxManagerProfileMessage";
    }

    @Override
    public String toString() {
      return getShortClassName() + " (processorId=" + processorId
          + "; profile=" + profile
          + ")";
    }
  }

  public static class JmxManagerProfile extends Profile {

    private boolean jmxManager;
    private String host;
    private int port;
    private boolean ssl;
    private boolean started;

    // Constructor for de-serialization
    public JmxManagerProfile() {}

    public boolean isJmxManager() {
      return jmxManager;
    }

    public boolean isJmxManagerRunning() {
      return started;
    }

    public void setInfo(boolean jmxManager2, String host2, int port2, boolean ssl2,
        boolean started2) {
      jmxManager = jmxManager2;
      host = host2;
      port = port2;
      ssl = ssl2;
      started = started2;
    }

    public String getHost() {
      return host;
    }

    public int getPort() {
      return port;
    }

    public boolean getSsl() {
      return ssl;
    }

    // Constructor for sending purposes
    public JmxManagerProfile(InternalDistributedMember memberId, int version) {
      super(memberId, version);
    }

    @Override
    public StringBuilder getToStringHeader() {
      return new StringBuilder("JmxManagerAdvisor.JmxManagerProfile");
    }

    @Override
    public void fillInToString(StringBuilder sb) {
      super.fillInToString(sb);
      synchronized (this) {
        if (jmxManager) {
          sb.append("; jmxManager");
        }
        sb.append("; host=").append(host).append("; port=").append(port);
        if (ssl) {
          sb.append("; ssl");
        }
        if (started) {
          sb.append("; started");
        }
      }
    }

    @Override
    public void processIncoming(ClusterDistributionManager dm, String adviseePath,
        boolean removeProfile, boolean exchangeProfiles, final List<Profile> replyProfiles) {
      final InternalCache cache = dm.getCache();
      if (cache != null && !cache.isClosed()) {
        handleDistributionAdvisee(cache.getJmxManagerAdvisor().getAdvisee(), removeProfile,
            exchangeProfiles, replyProfiles);
      }
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      jmxManager = DataSerializer.readPrimitiveBoolean(in);
      host = DataSerializer.readString(in);
      port = DataSerializer.readPrimitiveInt(in);
      ssl = DataSerializer.readPrimitiveBoolean(in);
      started = DataSerializer.readPrimitiveBoolean(in);
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      boolean tmpJmxManager;
      String tmpHost;
      int tmpPort;
      boolean tmpSsl;
      boolean tmpStarted;
      synchronized (this) {
        tmpJmxManager = jmxManager;
        tmpHost = host;
        tmpPort = port;
        tmpSsl = ssl;
        tmpStarted = started;
      }
      super.toData(out, context);
      DataSerializer.writePrimitiveBoolean(tmpJmxManager, out);
      DataSerializer.writeString(tmpHost, out);
      DataSerializer.writePrimitiveInt(tmpPort, out);
      DataSerializer.writePrimitiveBoolean(tmpSsl, out);
      DataSerializer.writePrimitiveBoolean(tmpStarted, out);
    }

    @Override
    public int getDSFID() {
      return JMX_MANAGER_PROFILE;
    }
  }

}
