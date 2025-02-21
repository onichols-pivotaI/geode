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
package org.apache.geode.cache.query.cq.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.apache.geode.InvalidDeltaException;
import org.apache.geode.StatisticsFactory;
import org.apache.geode.SystemFailure;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.CacheLoaderException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.RegionEvent;
import org.apache.geode.cache.TimeoutException;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.internal.GetEventValueOp;
import org.apache.geode.cache.client.internal.InternalPool;
import org.apache.geode.cache.client.internal.QueueManager;
import org.apache.geode.cache.client.internal.UserAttributes;
import org.apache.geode.cache.query.CqAttributes;
import org.apache.geode.cache.query.CqClosedException;
import org.apache.geode.cache.query.CqException;
import org.apache.geode.cache.query.CqExistsException;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.CqServiceStatistics;
import org.apache.geode.cache.query.CqStatusListener;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.QueryInvalidException;
import org.apache.geode.cache.query.RegionNotFoundException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.cq.internal.ops.ServerCQProxyImpl;
import org.apache.geode.cache.query.internal.CompiledSelect;
import org.apache.geode.cache.query.internal.CqQueryVsdStats;
import org.apache.geode.cache.query.internal.CqStateImpl;
import org.apache.geode.cache.query.internal.DefaultQuery;
import org.apache.geode.cache.query.internal.ExecutionContext;
import org.apache.geode.cache.query.internal.cq.ClientCQ;
import org.apache.geode.cache.query.internal.cq.CqService;
import org.apache.geode.cache.query.internal.cq.InternalCqQuery;
import org.apache.geode.cache.query.internal.cq.ServerCQ;
import org.apache.geode.distributed.internal.DistributionAdvisor.Profile;
import org.apache.geode.internal.cache.CacheDistributionAdvisor.CacheProfile;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.FilterProfile;
import org.apache.geode.internal.cache.FilterRoutingInfo;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.sockets.CacheClientNotifier;
import org.apache.geode.internal.cache.tier.sockets.CacheClientProxy;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.cache.tier.sockets.Part;
import org.apache.geode.internal.lang.utils.JavaWorkarounds;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * Implements the CqService functionality.
 *
 * @since GemFire 5.5
 */
public class CqServiceImpl implements CqService {
  private static final Logger logger = LogService.getLogger();

  private static final Integer MESSAGE_TYPE_LOCAL_CREATE = MessageType.LOCAL_CREATE;
  private static final Integer MESSAGE_TYPE_LOCAL_UPDATE = MessageType.LOCAL_UPDATE;
  private static final Integer MESSAGE_TYPE_LOCAL_DESTROY = MessageType.LOCAL_DESTROY;
  private static final Integer MESSAGE_TYPE_EXCEPTION = MessageType.EXCEPTION;

  /**
   * System property to evaluate the query even though the initial results are not required when cq
   * is executed using the execute() method.
   */
  public static boolean EXECUTE_QUERY_DURING_INIT = Boolean.parseBoolean(System
      .getProperty(GeodeGlossary.GEMFIRE_PREFIX + "cq.EXECUTE_QUERY_DURING_INIT", "true"));


  private static final String CQ_NAME_PREFIX = "GfCq";

  private final InternalCache cache;

  /**
   * Manages cq pools to determine if a status of connect or disconnect needs to be sent out
   */
  private final HashMap<String, Boolean> cqPoolsConnected = new HashMap<>();

  /**
   * Manages CQ objects. uses serverCqName as key and CqQueryImpl as value
   *
   * GuardedBy cqQueryMapLock
   */
  private volatile HashMap<String, CqQueryImpl> cqQueryMap = new HashMap<>();

  private final Object cqQueryMapLock = new Object();

  private volatile boolean isRunning = false;

  /**
   * Used by client when multiuser-authentication is true.
   */
  private final HashMap<String, UserAttributes> cqNameToUserAttributesMap = new HashMap<>();

  // Map to manage the similar CQs (having same query - performance optimization).
  // With query as key and Set of CQs as values.
  private final ConcurrentHashMap<String, Set<String>> matchingCqMap;

  // CQ Service statistics
  private final CqServiceStatisticsImpl cqServiceStats;
  private final CqServiceVsdStats stats;

  // CQ identifier, also used in auto generated CQ names
  private volatile long cqId = 1;

  /* This is to manage region to CQs map, client side book keeping. */
  private final HashMap<String, ArrayList<String>> baseRegionToCqNameMap = new HashMap<>();

  /**
   * Access and modification to the contents of this map do not necessarily need to be lock
   * protected. This is just used to optimize construction of a server side cq name. Missing values
   * in this cache will mean a look up for a specific proxy id and cq name will miss and reconstruct
   * the string before adding it back to the cache
   */
  private static final ConcurrentHashMap<String, ConcurrentHashMap<ClientProxyMembershipID, String>> serverCqNameCache =
      new ConcurrentHashMap<>();

  /**
   * Constructor.
   *
   * @param cache The cache used for the service
   */
  public CqServiceImpl(final InternalCache cache) {
    if (cache == null) {
      throw new IllegalStateException("cache is null");
    }
    cache.getCancelCriterion().checkCancelInProgress(null);

    this.cache = cache;

    // Initialize the Map which maintains the matching cqs.
    matchingCqMap = new ConcurrentHashMap<>();

    // Initialize the VSD statistics
    StatisticsFactory factory = this.cache.getDistributedSystem();
    stats = new CqServiceVsdStats(factory);
    cqServiceStats = new CqServiceStatisticsImpl(this);
  }

  /**
   * Returns the cache associated with the cqService.
   */
  public Cache getCache() {
    return cache;
  }

  public InternalCache getInternalCache() {
    return cache;
  }

  public CqServiceVsdStats stats() {
    return stats;
  }

  @Override
  public synchronized ClientCQ newCq(String cqName, String queryString, CqAttributes cqAttributes,
      InternalPool pool, boolean isDurable)
      throws QueryInvalidException, CqExistsException, CqException {
    if (queryString == null) {
      throw new IllegalArgumentException(
          String.format("Null argument %s", "queryString"));

    } else if (cqAttributes == null) {
      throw new IllegalArgumentException(
          String.format("Null argument %s", "cqAttribute"));
    }

    if (isServer()) {
      throw new IllegalStateException(
          "client side newCq() method invocation on server.");
    }

    // Check if the given cq already exists.
    if (cqName != null && isCqExists(cqName)) {
      throw new CqExistsException(
          String.format("CQ with the given name already exists. CqName : %s",
              cqName));
    }


    ServerCQProxyImpl serverProxy = pool == null ? null : new ServerCQProxyImpl(pool);
    ClientCQImpl cQuery =
        new ClientCQImpl(this, cqName, queryString, cqAttributes, serverProxy, isDurable);
    cQuery.updateCqCreateStats();

    // cQuery.initCq();

    // Check if query is valid.
    cQuery.validateCq();

    // Add cq into meta region.
    // Check if Name needs to be generated.
    if (cqName == null) {
      // in the case of cqname internally generated, the CqExistsException needs
      // to be taken care internally.
      while (true) {
        cQuery.setName(generateCqName());
        try {
          addToCqMap(cQuery);
        } catch (CqExistsException ex) {
          if (logger.isDebugEnabled()) {
            logger.debug("Got CqExistsException while intializing cq : {} Error : {}",
                cQuery.getName(), ex.getMessage());
          }
          continue;
        }
        break;
      }
    } else {
      addToCqMap(cQuery);
    }

    addToBaseRegionToCqNameMap(cQuery.getBaseRegionName(), cQuery.getServerCqName());

    return cQuery;
  }

  /**
   * Executes the given CqQuery, if the CqQuery for that name is not there it registers the one and
   * executes. This is called on the Server.
   *
   * @param manageEmptyRegions whether to update the 6.1 emptyRegions map held in the CCN
   * @param regionDataPolicy the data policy of the region associated with the query. This is only
   *        needed if manageEmptyRegions is true.
   * @param emptyRegionsMap map of empty regions.
   * @throws IllegalStateException if this is called at client side.
   */
  @Override
  public synchronized ServerCQ executeCq(final @NotNull String cqName,
      final @NotNull String queryString, final int cqState,
      final @NotNull ClientProxyMembershipID clientProxyId,
      final @Nullable CacheClientNotifier ccn,
      final boolean isDurable,
      final boolean manageEmptyRegions,
      @Nullable DataPolicy regionDataPolicy,
      final @NotNull Map<String, Integer> emptyRegionsMap)
      throws CqException, RegionNotFoundException, CqClosedException {
    if (!isServer()) {
      throw new IllegalStateException(
          String.format("Server side executeCq method is called on client. CqName : %s",
              cqName));
    }

    final String serverCqName = constructServerCqName(cqName, clientProxyId);
    final ServerCQImpl cQuery;

    // If this CQ is not yet registered in Server, register CQ.
    if (!isCqExists(serverCqName)) {
      cQuery = new ServerCQImpl(this, cqName, queryString, isDurable,
          constructServerCqName(cqName, clientProxyId));

      try {
        cQuery.registerCq(clientProxyId, ccn, cqState);
        if (manageEmptyRegions) {
          if (emptyRegionsMap.containsKey(cQuery.getBaseRegionName())) {
            regionDataPolicy = DataPolicy.EMPTY;
          }

          final CacheClientProxy proxy = getCacheClientProxy(clientProxyId, ccn);
          ccn.updateMapOfEmptyRegions(
              proxy.getRegionsWithEmptyDataPolicy(),
              cQuery.getBaseRegionName(), regionDataPolicy);
        }
      } catch (CqException cqe) {
        logger.info("Exception while registering CQ on server. CqName : {}",
            cQuery.getName());
        throw cqe;
      }

    } else {
      cQuery = (ServerCQImpl) getCq(serverCqName);
      resumeCQ(cqState, cQuery);
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Successfully created CQ on the server. CqName : {}", cQuery.getName());
    }

    return cQuery;
  }

  public CacheClientProxy getCacheClientProxy(ClientProxyMembershipID clientProxyId,
      CacheClientNotifier ccn) throws CqException {
    CacheClientProxy proxy = ccn.getClientProxy(clientProxyId, true);
    if (proxy == null) {
      throw new CqException("No Cache Client Proxy found while executing CQ.");
    }
    return proxy;
  }

  @Override
  public void resumeCQ(int cqState, ServerCQ cQuery) {
    // Initialize the state of CQ.
    if (((CqStateImpl) cQuery.getState()).getState() != cqState) {
      cQuery.setCqState(cqState);
      // addToCqEventKeysMap(cQuery);
      // Send state change info to peers.
      cQuery.getCqBaseRegion().getFilterProfile().setCqState(cQuery);
    }
    // If we are going to set the state to running, we need to check to see if it matches any other
    // cq
    if (cqState == CqStateImpl.RUNNING) {
      // Add to the matchedCqMap.
      addToMatchingCqMap((CqQueryImpl) cQuery);
    }
  }

  /**
   * Adds the given CQ and cqQuery object into the CQ map.
   */
  void addToCqMap(CqQueryImpl cq) throws CqExistsException, CqException {
    // On server side cqName will be server side cqName.
    String sCqName = cq.getServerCqName();
    if (logger.isDebugEnabled()) {
      logger.debug("Adding to CQ Repository. CqName : {} ServerCqName : {}", cq.getName(), sCqName);
    }
    HashMap<String, CqQueryImpl> cqMap = cqQueryMap;
    if (cqMap.containsKey(sCqName)) {
      throw new CqExistsException(
          String.format("A CQ with the given name %s already exists.",
              sCqName));
    }
    synchronized (cqQueryMapLock) {
      HashMap<String, CqQueryImpl> tmpCqQueryMap = new HashMap<>(cqQueryMap);
      try {
        tmpCqQueryMap.put(sCqName, cq);
      } catch (Exception ex) {
        String errMsg =
            "Failed to store Continuous Query in the repository. CqName: %s %s";
        Object[] errMsgArgs = new Object[] {sCqName, ex.getLocalizedMessage()};
        String s = String.format(errMsg, errMsgArgs);
        logger.error(s);
        throw new CqException(s, ex);
      }
      UserAttributes attributes = UserAttributes.userAttributes.get();
      if (attributes != null) {
        cqNameToUserAttributesMap.put(cq.getName(), attributes);
      }
      cqQueryMap = tmpCqQueryMap;
    }
  }

  /**
   * Removes given CQ from the cqMap..
   */
  void removeCq(String cqName) {
    // On server side cqName will be server side cqName.
    synchronized (cqQueryMapLock) {
      HashMap<String, CqQueryImpl> tmpCqQueryMap = new HashMap<>(cqQueryMap);
      tmpCqQueryMap.remove(cqName);
      cqNameToUserAttributesMap.remove(cqName);
      cqQueryMap = tmpCqQueryMap;
    }
  }

  @Override
  public CqQuery getClientCqFromServer(ClientProxyMembershipID clientProxyId, String clientCqName) {
    // On server side cqName will be server side cqName.
    HashMap<String, CqQueryImpl> cqMap = cqQueryMap;
    return cqMap.get(constructServerCqName(clientCqName, clientProxyId));
  }

  @Override
  public InternalCqQuery getCq(String cqName) {
    // On server side cqName will be server side cqName.
    return cqQueryMap.get(cqName);
  }

  @Override
  public Collection<? extends InternalCqQuery> getAllCqs() {
    return cqQueryMap.values();
  }

  @Override
  public Collection<? extends InternalCqQuery> getAllCqs(final String regionName)
      throws CqException {
    if (regionName == null) {
      throw new IllegalArgumentException(
          String.format("Null argument %s", "regionName"));
    }

    String[] cqNames;

    synchronized (baseRegionToCqNameMap) {
      ArrayList<String> cqs = baseRegionToCqNameMap.get(regionName);
      if (cqs == null) {
        return null;
      }
      cqNames = new String[cqs.size()];
      cqs.toArray(cqNames);
    }

    ArrayList<InternalCqQuery> cQueryList = new ArrayList<>();
    for (String cqName : cqNames) {
      InternalCqQuery cq = getCq(cqName);
      if (cq != null) {
        cQueryList.add(cq);
      }
    }

    return cQueryList;
  }

  @Override
  public synchronized void executeAllClientCqs() throws CqException {
    executeCqs(getAllCqs());
  }

  @Override
  public synchronized void executeAllRegionCqs(final String regionName) throws CqException {
    executeCqs(getAllCqs(regionName));
  }

  @Override
  public synchronized void executeCqs(Collection<? extends InternalCqQuery> cqs)
      throws CqException {
    if (cqs == null) {
      return;
    }
    String cqName = null;
    for (CqQuery cq : cqs) {
      if (!cq.isClosed() && cq.isStopped()) {
        try {
          cqName = cq.getName();
          cq.execute();
        } catch (QueryException | CqClosedException e) {
          if (logger.isDebugEnabled()) {
            logger.debug("Failed to execute the CQ, CqName : {} Error : {}", cqName,
                e.getMessage());
          }
        }
      }
    }
  }

  @Override
  public synchronized void stopAllClientCqs() throws CqException {
    stopCqs(getAllCqs());
  }

  @Override
  public synchronized void stopAllRegionCqs(final String regionName) throws CqException {
    stopCqs(getAllCqs(regionName));
  }

  @Override
  public synchronized void stopCqs(Collection<? extends InternalCqQuery> cqs) throws CqException {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      if (cqs == null) {
        logger.debug("CqService.stopCqs cqs : null");
      } else {
        logger.debug("CqService.stopCqs cqs : ({} queries)", cqs.size());
      }
    }

    if (cqs == null) {
      return;
    }

    String cqName = null;
    for (CqQuery cq : cqs) {
      if (!cq.isClosed() && cq.isRunning()) {
        try {
          cqName = cq.getName();
          cq.stop();
        } catch (QueryException | CqClosedException e) {
          if (isDebugEnabled) {
            logger.debug("Failed to stop the CQ, CqName : {} Error : {}", cqName, e.getMessage());
          }
        }
      }
    }
  }

  @Override
  public void closeCqs(final String regionName) throws CqException {
    Collection<? extends InternalCqQuery> cqs = getAllCqs(regionName);
    if (cqs != null) {
      String cqName = null;
      for (InternalCqQuery cq : cqs) {
        try {
          cqName = cq.getName();

          if (isServer()) {
            // invoked on the server
            cq.close(false);
          } else {
            // TODO: grid: if regionName has a pool check its keepAlive
            boolean keepAlive = cache.keepDurableSubscriptionsAlive();
            if (cq.isDurable() && keepAlive) {
              logger.warn("Not sending CQ close to the server as it is a durable CQ");
              cq.close(false);
            } else {
              cq.close(true);
            }
          }

        } catch (QueryException | CqClosedException e) {
          if (logger.isDebugEnabled()) {
            logger.debug("Failed to close the CQ, CqName : {} Error : {}", cqName, e.getMessage());
          }
        }
      }
    }
  }

  /**
   * Called directly on server side.
   */
  @Override
  public void stopCq(String cqName, ClientProxyMembershipID clientId) throws CqException {
    String serverCqName = cqName;
    if (clientId != null) {
      serverCqName = constructServerCqName(cqName, clientId);
      removeFromCacheForServerToConstructedCQName(cqName, clientId);
    }

    ServerCQImpl cQuery = null;
    String errMsg = null;
    Exception ex = null;

    try {
      HashMap<String, CqQueryImpl> cqMap = cqQueryMap;
      if (!cqMap.containsKey(serverCqName)) {
        /*
         * gregp 052808: We should silently fail here instead of throwing error. This is to deal
         * with races in recovery
         */
        return;
      }
      cQuery = (ServerCQImpl) getCq(serverCqName);

    } catch (CacheLoaderException e1) {
      errMsg = "CQ not found in the cq meta region, CqName: %s";
      ex = e1;
    } catch (TimeoutException e2) {
      errMsg = "Timeout while trying to get CQ from meta region, CqName: %s";
      ex = e2;
    } finally {
      if (ex != null) {
        String s = String.format(errMsg, cqName);
        if (logger.isDebugEnabled()) {
          logger.debug(s);
        }
        throw new CqException(s, ex);
      }
    }

    try {
      if (!cQuery.isStopped()) {
        cQuery.stop();
      }
    } catch (CqClosedException cce) {
      throw new CqException(cce.getMessage());
    } finally {
      // If this CQ is stopped, disable caching event keys for this CQ.
      // this.removeCQFromCaching(cQuery.getServerCqName());
      removeFromMatchingCqMap(cQuery);
    }
    // Send stop message to peers.
    cQuery.getCqBaseRegion().getFilterProfile().stopCq(cQuery);
  }

  @Override
  public void closeCq(String cqName, ClientProxyMembershipID clientProxyId) throws CqException {
    String serverCqName = cqName;
    if (clientProxyId != null) {
      serverCqName = constructServerCqName(cqName, clientProxyId);
      removeFromCacheForServerToConstructedCQName(cqName, clientProxyId);
    }

    ServerCQImpl cQuery = null;
    String errMsg = null;
    Exception ex = null;

    try {
      HashMap<String, CqQueryImpl> cqMap = cqQueryMap;
      if (!cqMap.containsKey(serverCqName)) {
        /*
         * gregp 052808: We should silently fail here instead of throwing error. This is to deal
         * with races in recovery
         */
        return;
      }
      cQuery = (ServerCQImpl) cqMap.get(serverCqName);

    } catch (CacheLoaderException e1) {
      errMsg = "CQ not found in the cq meta region, CqName: %s";
      ex = e1;
    } catch (TimeoutException e2) {
      errMsg = "Timeout while trying to get CQ from meta region, CqName: %s";
      ex = e2;
    } finally {
      if (ex != null) {
        String s = String.format(errMsg, cqName);
        if (logger.isDebugEnabled()) {
          logger.debug(s);
        }
        throw new CqException(s, ex);
      }
    }

    try {
      cQuery.close(false);

      // Repository Region.
      // If CQ event caching is enabled, remove this CQs event cache reference.
      // removeCQFromCaching(serverCqName);

      // CqBaseRegion
      try {
        LocalRegion baseRegion = cQuery.getCqBaseRegion();
        if (baseRegion != null && !baseRegion.isDestroyed()) {
          // Server specific clean up.
          if (isServer()) {
            FilterProfile fp = baseRegion.getFilterProfile();
            if (fp != null) {
              fp.closeCq(cQuery);
            }
            CacheClientProxy clientProxy =
                cQuery.getCacheClientNotifier().getClientProxy(clientProxyId);
            clientProxy.decCqCount();
            if (clientProxy.hasNoCq()) {
              stats.decClientsWithCqs();
            }
          }
        }
      } catch (Exception e) {
        // May be cache is being shutdown
        if (logger.isDebugEnabled()) {
          logger.debug("Failed to remove CQ from the base region. CqName : {}", cqName);
        }
      }

      if (isServer()) {
        removeFromBaseRegionToCqNameMap(cQuery.getRegionName(), serverCqName);
      }

      LocalRegion baseRegion = cQuery.getCqBaseRegion();
      if (baseRegion.getFilterProfile().getCqCount() <= 0) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Should update the profile for this partitioned region {} for not requiring old value",
              baseRegion);
        }
      }
    } catch (CqClosedException cce) {
      throw new CqException(cce.getMessage());
    } finally {
      removeFromMatchingCqMap(cQuery);
    }
  }

  @Override
  public void closeAllCqs(boolean clientInitiated) {
    closeAllCqs(clientInitiated, getAllCqs());
  }

  /**
   * Close all CQs executing in this VM, and release resources associated with executing CQs.
   * CqQuerys created by other VMs are unaffected.
   */
  private void closeAllCqs(boolean clientInitiated, Collection<? extends InternalCqQuery> cqs) {
    closeAllCqs(clientInitiated, cqs, cache.keepDurableSubscriptionsAlive());
  }

  @Override
  public void closeAllCqs(boolean clientInitiated, Collection<? extends InternalCqQuery> cqs,
      boolean keepAlive) {

    if (cqs != null) {
      String cqName = null;
      if (logger.isDebugEnabled()) {
        logger.debug("Closing all CQs, number of CQ to be closed : {}", cqs.size());
      }
      for (InternalCqQuery cQuery : cqs) {
        try {
          cqName = cQuery.getName();

          if (isServer()) {
            cQuery.close(false);
          } else {
            if (clientInitiated) {
              cQuery.close(true);
            } else {
              if (!isServer() && cQuery.isDurable() && keepAlive) {
                logger.warn("Not sending CQ close to the server as it is a durable CQ");
                cQuery.close(false);
              } else {
                cQuery.close(true);
              }
            }
          }
        } catch (QueryException | CqClosedException e) {
          if (!isRunning()) {
            // Not cache shutdown
            logger
                .warn("Failed to close CQ {} : {}",
                    cqName, e.getMessage());
          }
          if (logger.isDebugEnabled()) {
            logger.debug(e.getMessage(), e);
          }
        }
      }
    }
  }

  @Override
  public CqServiceStatistics getCqStatistics() {
    return cqServiceStats;
  }

  @Override
  public void closeClientCqs(ClientProxyMembershipID clientProxyId) throws CqException {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("Closing Client CQs for the client: {}", clientProxyId);
    }
    List<ServerCQ> cqs = getAllClientCqs(clientProxyId);
    for (ServerCQ cq : cqs) {
      CqQueryImpl cQuery = (CqQueryImpl) cq;
      try {
        cQuery.close(false);
      } catch (QueryException | CqClosedException e) {
        if (isDebugEnabled) {
          logger.debug("Failed to close the CQ, CqName : {} Error : {}", cQuery.getName(),
              e.getMessage());
        }
      }
    }
  }

  @Override
  public List<ServerCQ> getAllClientCqs(ClientProxyMembershipID clientProxyId) {
    Collection<? extends InternalCqQuery> cqs = getAllCqs();
    ArrayList<ServerCQ> clientCqs = new ArrayList<>();

    for (InternalCqQuery cq : cqs) {
      ServerCQImpl cQuery = (ServerCQImpl) cq;
      ClientProxyMembershipID id = cQuery.getClientProxyId();
      if (id != null && id.equals(clientProxyId)) {
        clientCqs.add(cQuery);
      }
    }
    return clientCqs;
  }

  @Override
  public List<String> getAllDurableClientCqs(ClientProxyMembershipID clientProxyId)
      throws CqException {
    if (clientProxyId == null) {
      throw new CqException(
          String.format("Unable to retrieve durable CQs for client proxy id %s", clientProxyId));
    }
    List<ServerCQ> cqs = getAllClientCqs(clientProxyId);
    ArrayList<String> durableClientCqs = new ArrayList<>();

    for (ServerCQ cq : cqs) {
      ServerCQImpl cQuery = (ServerCQImpl) cq;
      if (cQuery != null && cQuery.isDurable()) {
        ClientProxyMembershipID id = cQuery.getClientProxyId();
        if (id != null && id.equals(clientProxyId)) {
          durableClientCqs.add(cQuery.getName());
        }
      }
    }
    return durableClientCqs;
  }

  /**
   * Server side method. Closes non-durable CQs for the given client proxy id.
   */
  @Override
  public void closeNonDurableClientCqs(ClientProxyMembershipID clientProxyId) throws CqException {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("Closing Client CQs for the client: {}", clientProxyId);
    }
    List<ServerCQ> cqs = getAllClientCqs(clientProxyId);
    for (ServerCQ cq : cqs) {
      ServerCQImpl cQuery = (ServerCQImpl) cq;
      try {
        if (!cQuery.isDurable()) {
          cQuery.close(false);
        }
      } catch (QueryException | CqClosedException e) {
        if (isDebugEnabled) {
          logger.debug("Failed to close the CQ, CqName : {} Error : {}", cQuery.getName(),
              e.getMessage());
        }
      }
    }
  }

  /**
   * Is the CQ service in a cache server environment
   *
   * @return true if cache server, false otherwise
   */
  public boolean isServer() {
    return !cache.getCacheServers().isEmpty();
  }

  /**
   * Cleans up the CqService.
   */
  @Override
  public void close() {
    if (logger.isDebugEnabled()) {
      logger.debug("Closing CqService. {}", this);
    }
    // Close All the CQs.
    // Need to take care when Clients are still connected...
    closeAllCqs(false);
    isRunning = false;
  }

  @Override
  public boolean isRunning() {
    return isRunning;
  }

  @Override
  public void start() {
    isRunning = true;
  }

  /**
   * @return Returns the serverCqName.
   */
  @Override
  public String constructServerCqName(String cqName, ClientProxyMembershipID clientProxyId) {
    ConcurrentHashMap<ClientProxyMembershipID, String> cache =
        JavaWorkarounds.computeIfAbsent(serverCqNameCache, cqName,
            key -> new ConcurrentHashMap<>());

    String cName = cache.get(clientProxyId);
    if (null == cName) {
      final StringBuilder sb = new StringBuilder(cqName).append("__");
      if (clientProxyId.isDurable()) {
        sb.append(clientProxyId.getDurableId());
      } else {
        sb.append(clientProxyId.getDSMembership());
      }
      cName = sb.toString();
      cache.put(clientProxyId, cName);
    }

    return cName;
  }

  private void removeFromCacheForServerToConstructedCQName(final String cqName,
      ClientProxyMembershipID clientProxyMembershipID) {
    ConcurrentHashMap<ClientProxyMembershipID, String> cache = serverCqNameCache.get(cqName);
    if (cache != null) {
      cache.remove(clientProxyMembershipID);
      if (cache.size() == 0) {
        serverCqNameCache.remove(cqName);
      }
    }
  }

  /**
   * Checks if CQ with the given name already exists.
   *
   * @param cqName name of the CQ.
   *
   * @return true if exists else false.
   */
  private synchronized boolean isCqExists(String cqName) {
    HashMap<String, CqQueryImpl> cqMap = cqQueryMap;
    return cqMap.containsKey(cqName);
  }

  /**
   * Generates a name for CQ. Checks if CQ with that name already exists if so generates a new
   * cqName.
   */
  private synchronized String generateCqName() {
    while (true) {
      String cqName = CQ_NAME_PREFIX + (cqId++);
      if (!isCqExists(cqName)) {
        return cqName;
      }
    }
  }

  @Override
  public void dispatchCqListeners(HashMap<String, Integer> cqs, int messageType, Object key,
      Object value, byte[] delta, QueueManager qManager, EventID eventId) {
    Object[] fullValue = new Object[1];
    Iterator<Map.Entry<String, Integer>> iter = cqs.entrySet().iterator();
    String cqName = null;
    final boolean isDebugEnabled = logger.isDebugEnabled();
    while (iter.hasNext()) {
      try {
        Map.Entry<String, Integer> entry = iter.next();
        cqName = entry.getKey();
        ClientCQImpl cQuery = (ClientCQImpl) getCq(cqName);

        if (cQuery == null || (!cQuery.isRunning() && cQuery.getQueuedEvents() == null)) {
          if (isDebugEnabled) {
            logger.debug("Unable to invoke CqListener, {}, CqName : {}",
                ((cQuery == null) ? "CQ not found" : " CQ is Not running"), cqName);
          }
          continue;
        }

        Integer cqOp = entry.getValue();

        // If Region destroy event, close the cq.
        if (cqOp == MessageType.DESTROY_REGION) {
          // The close will also invoke the listeners close().
          try {
            cQuery.close(false);
          } catch (Exception ex) {
            // handle?
          }
          continue;
        }

        // Construct CqEvent.
        CqEventImpl cqEvent = new CqEventImpl(cQuery, getOperation(messageType), getOperation(cqOp),
            key, value, delta, qManager, eventId);

        // Update statistics
        cQuery.updateStats(cqEvent);

        // Check if CQ Event needs to be queued.
        if (cQuery.getQueuedEvents() != null) {
          synchronized (cQuery.queuedEventsSynchObject) {
            // Get latest value.
            ConcurrentLinkedQueue<CqEventImpl> queuedEvents = cQuery.getQueuedEvents();
            // Check to see, if its not set to null while waiting to get
            // Synchronization lock.
            if (queuedEvents != null) {
              if (isDebugEnabled) {
                logger.debug("Queueing event for key: {}", key);
              }
              cQuery.getVsdStats().incQueuedCqListenerEvents();
              queuedEvents.add(cqEvent);
              continue;
            }
          }
        }

        invokeListeners(cqName, cQuery, cqEvent, fullValue);
        if (value == null) {
          value = fullValue[0];
        }

      } // outer try
      catch (Throwable t) {
        logger.warn(String.format("Error processing CqListener for cq: %s", cqName), t);

        if (t instanceof VirtualMachineError) {
          logger.warn(String.format("VirtualMachineError processing CqListener for cq: %s",
              cqName),
              t);
          return;
        }
      }
    } // iteration.
  }

  void invokeListeners(String cqName, ClientCQImpl cQuery, CqEventImpl cqEvent) {
    invokeListeners(cqName, cQuery, cqEvent, null);
  }

  private void invokeListeners(String cqName, ClientCQImpl cQuery, CqEventImpl cqEvent,
      Object[] fullValue) {
    if (!cQuery.isRunning() || cQuery.getCqAttributes() == null) {
      return;
    }
    // invoke CQ Listeners.
    CqListener[] cqListeners = cQuery.getCqAttributes().getCqListeners();

    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("Invoking CQ listeners for {}, number of listeners : {} cqEvent : {}", cqName,
          cqListeners.length, cqEvent);
    }

    for (CqListener cqListener : cqListeners) {
      try {
        // Check if the listener is not null, it could have been changed/reset
        // by the CqAttributeMutator.
        if (cqListener != null) {
          cQuery.getVsdStats().incNumCqListenerInvocations();
          try {
            if (cqEvent.getThrowable() != null) {
              cqListener.onError(cqEvent);
            } else {
              cqListener.onEvent(cqEvent);
            }
          } catch (InvalidDeltaException ide) {
            if (isDebugEnabled) {
              logger.debug("CqService.dispatchCqListeners(): Requesting full value...");
            }
            Part result = (Part) GetEventValueOp
                .executeOnPrimary(cqEvent.getQueueManager().getPool(), cqEvent.getEventID(), null);
            Object newVal = result.getObject();
            if (result == null || newVal == null) {
              if (!cache.getCancelCriterion().isCancelInProgress()) {
                Exception ex =
                    new Exception("Failed to retrieve full value from server for eventID "
                        + cqEvent.getEventID());
                logger.warn("Exception in the CqListener of the CQ, CqName: {} Error : {}",
                    new Object[] {cqName, ex.getMessage()});
                if (isDebugEnabled) {
                  logger.debug(ex.getMessage(), ex);
                }
              }
            } else {
              cache.getCachePerfStats().incDeltaFullValuesRequested();
              cqEvent = new CqEventImpl(cQuery, cqEvent.getBaseOperation(),
                  cqEvent.getQueryOperation(), cqEvent.getKey(), newVal, cqEvent.getDeltaValue(),
                  cqEvent.getQueueManager(), cqEvent.getEventID());
              if (cqEvent.getThrowable() != null) {
                cqListener.onError(cqEvent);
              } else {
                cqListener.onEvent(cqEvent);
              }
              if (fullValue != null) {
                fullValue[0] = newVal;
              }
            }
          }
        }
        // Handle client side exceptions.
      } catch (Exception ex) {
        if (!cache.getCancelCriterion().isCancelInProgress()) {
          logger.warn("Exception in the CqListener of the CQ, CqName: {} Error : {}",
              new Object[] {cqName, ex.getMessage()});
          if (isDebugEnabled) {
            logger.debug(ex.getMessage(), ex);
          }
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
        logger.warn("Runtime Exception in the CqListener of the CQ, CqName: {} Error : {}",
            new Object[] {cqName, t.getLocalizedMessage()});
        if (isDebugEnabled) {
          logger.debug(t.getMessage(), t);
        }
      }
    }
  }

  private void invokeCqConnectedListeners(String cqName, ClientCQImpl cQuery, boolean connected) {
    if (!cQuery.isRunning() || cQuery.getCqAttributes() == null) {
      return;
    }
    cQuery.setConnected(connected);
    // invoke CQ Listeners.
    CqListener[] cqListeners = cQuery.getCqAttributes().getCqListeners();

    if (logger.isDebugEnabled()) {
      logger.debug("Invoking CQ status listeners for {}, number of listeners : {}", cqName,
          cqListeners.length);
    }

    for (CqListener cqListener : cqListeners) {
      try {
        if (cqListener != null) {
          if (cqListener instanceof CqStatusListener) {
            CqStatusListener listener = (CqStatusListener) cqListener;
            if (connected) {
              listener.onCqConnected();
            } else {
              listener.onCqDisconnected();
            }
          }
        }
        // Handle client side exceptions.
      } catch (Exception ex) {
        if (!cache.getCancelCriterion().isCancelInProgress()) {
          logger.warn("Exception in the CqListener of the CQ, CqName: {} Error : {}",
              new Object[] {cqName, ex.getMessage()});
          if (logger.isDebugEnabled()) {
            logger.debug(ex.getMessage(), ex);
          }
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
        logger.warn("Runtime Exception in the CqListener of the CQ, CqName: {} Error : {}",
            new Object[] {cqName, t.getLocalizedMessage()});
        if (logger.isDebugEnabled()) {
          logger.debug(t.getMessage(), t);
        }
      }
    }
  }

  /**
   * Returns the Operation for the given EnumListenerEvent type.
   */
  private Operation getOperation(int eventType) {
    Operation op = null;
    switch (eventType) {
      case MessageType.LOCAL_CREATE:
        op = Operation.CREATE;
        break;

      case MessageType.LOCAL_UPDATE:
        op = Operation.UPDATE;
        break;

      case MessageType.LOCAL_DESTROY:
        op = Operation.DESTROY;
        break;

      case MessageType.LOCAL_INVALIDATE:
        op = Operation.INVALIDATE;
        break;

      case MessageType.CLEAR_REGION:
        op = Operation.REGION_CLEAR;
        break;

      case MessageType.INVALIDATE_REGION:
        op = Operation.REGION_INVALIDATE;
        break;
    }
    return op;
  }

  @Override
  public void processEvents(CacheEvent<?, ?> event, Profile localProfile, Profile[] profiles,
      FilterRoutingInfo frInfo) throws CqException {
    // Is this a region event or an entry event
    if (event instanceof RegionEvent) {
      processRegionEvent(event, localProfile, profiles, frInfo);
    } else {
      // Use the PDX types in serialized form.
      Boolean initialPdxReadSerialized = cache.getPdxReadSerializedOverride();
      cache.setPdxReadSerializedOverride(true);
      try {
        processEntryEvent(event, localProfile, profiles, frInfo);
      } finally {
        cache.setPdxReadSerializedOverride(initialPdxReadSerialized);
      }
    }
  }

  private void processRegionEvent(CacheEvent event, Profile localProfile, Profile[] profiles,
      FilterRoutingInfo frInfo) throws CqException {

    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("CQ service processing region event {}", event);
    }
    Integer cqRegionEvent = generateCqRegionEvent(event);

    for (int i = -1; i < profiles.length; i++) {
      CacheProfile cf;
      if (i < 0) {
        cf = (CacheProfile) localProfile;
        if (cf == null) {
          continue;
        }
      } else {
        cf = (CacheProfile) profiles[i];
      }
      FilterProfile pf = cf.filterProfile;
      if (pf == null || pf.getCqMap().isEmpty()) {
        continue;
      }
      Map cqs = pf.getCqMap();
      HashMap<Long, Integer> cqInfo = new HashMap<>();
      for (final Object o : cqs.entrySet()) {
        Map.Entry cqEntry = (Map.Entry) o;
        ServerCQImpl cQuery = (ServerCQImpl) cqEntry.getValue();
        if (!event.isOriginRemote() && event.getOperation().isRegionDestroy()
            && !((LocalRegion) event.getRegion()).isUsedForPartitionedRegionBucket()) {
          try {
            if (isDebugEnabled) {
              logger.debug("Closing CQ on region destroy event. CqName : {}", cQuery.getName());
            }
            cQuery.close(false);
          } catch (Exception ex) {
            if (isDebugEnabled) {
              logger.debug("Failed to Close CQ on region destroy. CqName : {}", cQuery.getName(),
                  ex);
            }
          }
        }
        cqInfo.put(cQuery.getFilterID(), cqRegionEvent);
        cQuery.getVsdStats().updateStats(cqRegionEvent);
      }
      if (pf.isLocalProfile()) {
        frInfo.setLocalCqInfo(cqInfo);
      } else {
        frInfo.setCqRoutingInfo(cf.getDistributedMember(), cqInfo);
      }
    }
  }

  private void processEntryEvent(CacheEvent event, Profile localProfile, Profile[] profiles,
      FilterRoutingInfo frInfo) throws CqException {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    HashSet<Object> cqUnfilteredEventsSet_newValue = new HashSet<>();
    HashSet<Object> cqUnfilteredEventsSet_oldValue = new HashSet<>();
    boolean b_cqResults_newValue;
    boolean b_cqResults_oldValue;
    boolean queryOldValue;
    EntryEvent entryEvent = (EntryEvent) event;
    Object eventKey = entryEvent.getKey();

    boolean isDupEvent = ((EntryEventImpl) event).isPossibleDuplicate();
    // The CQ query needs to be applied when the op is update, destroy
    // invalidate and in case when op is create and its an duplicate
    // event, the reason for this is when peer sends a duplicate event
    // it marks it as create and sends it, so that the receiving node
    // applies it (see DR.virtualPut()).
    boolean opRequiringQueryOnOldValue = (event.getOperation().isUpdate()
        || event.getOperation().isDestroy() || event.getOperation().isInvalidate()
        || (event.getOperation().isCreate() && isDupEvent));

    HashMap<String, Integer> matchedCqs = new HashMap<>();
    long executionStartTime;
    for (int i = -1; i < profiles.length; i++) {
      CacheProfile cf;
      if (i < 0) {
        cf = (CacheProfile) localProfile;
        if (cf == null) {
          continue;
        }
      } else {
        cf = (CacheProfile) profiles[i];
      }
      FilterProfile pf = cf.filterProfile;
      if (pf == null || pf.getCqMap().isEmpty()) {
        continue;
      }

      Map cqs = pf.getCqMap();

      if (isDebugEnabled) {
        logger.debug("Profile for {} processing {} CQs", cf.peerMemberId, cqs.size());
      }

      if (cqs.isEmpty()) {
        continue;
      }

      // Get new value. If its not retrieved.
      if (cqUnfilteredEventsSet_newValue.isEmpty()
          && (event.getOperation().isCreate() || event.getOperation().isUpdate())) {
        Object newValue = entryEvent.getNewValue();
        if (newValue != null) {
          // We have a new value to run the query on
          cqUnfilteredEventsSet_newValue.add(newValue);
        }
      }

      HashMap<Long, Integer> cqInfo = new HashMap<>();

      for (Object o : cqs.entrySet()) {
        Map.Entry cqEntry = (Map.Entry) o;
        ServerCQImpl cQuery = (ServerCQImpl) cqEntry.getValue();
        b_cqResults_newValue = false;
        b_cqResults_oldValue = false;
        queryOldValue = false;
        if (cQuery == null) {
          continue;
        }
        String cqName = cQuery.getServerCqName();
        Long filterID = cQuery.getFilterID();

        if (isDebugEnabled) {
          logger.debug("Processing CQ : {} Key: {}", cqName, eventKey);
        }

        Integer cqEvent = null;
        if (matchedCqs.containsKey(cqName)) {
          cqEvent = matchedCqs.get(cqName);
          if (isDebugEnabled) {
            logger.debug("query {} has already been processed and returned {}", cqName, cqEvent);
          }
          if (cqEvent == null) {
            continue;
          }
          // Update the Cache Results for this CQ.
          if (cqEvent == MessageType.LOCAL_CREATE
              || cqEvent == MessageType.LOCAL_UPDATE) {
            cQuery.addToCqResultKeys(eventKey);
          } else if (cqEvent == MessageType.LOCAL_DESTROY) {
            cQuery.markAsDestroyedInCqResultKeys(eventKey);
          }
        } else {
          boolean error = false;
          {
            try {
              // Apply query on new value.
              if (!cqUnfilteredEventsSet_newValue.isEmpty()) {
                executionStartTime = stats.startCqQueryExecution();

                synchronized (cQuery) {
                  b_cqResults_newValue =
                      evaluateQuery(cQuery, new Object[] {cqUnfilteredEventsSet_newValue});
                }

                stats.endCqQueryExecution(executionStartTime);
              }

              // In case of Update, destroy and invalidate.
              // Apply query on oldValue.
              if (opRequiringQueryOnOldValue) {
                // Check if CQ Result is cached, if not apply query on old
                // value. Currently the CQ Results are not cached for the
                // Partitioned Regions. Once this is added remove the check
                // with PR region.
                if (cQuery.isCqResultsCacheInitialized()) {
                  b_cqResults_oldValue =
                      (cQuery.isPartOfCqResult(eventKey) && !cQuery.isKeyDestroyed(eventKey));
                  // For PR if not found in cache, apply the query on old value.
                  // Also apply if the query was not executed during cq execute
                  if ((cQuery.isPR || !CqServiceImpl.EXECUTE_QUERY_DURING_INIT)
                      && b_cqResults_oldValue == false) {
                    queryOldValue = true;
                  }
                  if (isDebugEnabled && !cQuery.isPR && !b_cqResults_oldValue) {
                    logger.debug(
                        "Event Key not found in the CQ Result Queue. EventKey : {} CQ Name : {}",
                        eventKey, cqName);
                  }
                } else {
                  queryOldValue = true;
                }

                if (queryOldValue) {
                  if (cqUnfilteredEventsSet_oldValue.isEmpty()) {
                    Object oldValue = entryEvent.getOldValue();
                    if (oldValue != null) {
                      cqUnfilteredEventsSet_oldValue.add(oldValue);
                    }
                  }

                  // Apply query on old value.
                  if (!cqUnfilteredEventsSet_oldValue.isEmpty()) {
                    executionStartTime = stats.startCqQueryExecution();

                    synchronized (cQuery) {
                      b_cqResults_oldValue =
                          evaluateQuery(cQuery, new Object[] {cqUnfilteredEventsSet_oldValue});
                    }

                    stats.endCqQueryExecution(executionStartTime);
                  } else {
                    if (isDebugEnabled) {
                      logger.debug(
                          "old value for event with key {} is null - query execution not performed",
                          eventKey);
                    }
                  }
                } // Query oldValue
              }
            } catch (Exception ex) {
              // Any exception in running the query should be caught here and
              // buried because this code is running in-line with the message
              // processing code and we don't want to kill that thread
              error = true;
              // CHANGE LOG MESSAGE:
              logger.info("Error while processing CQ on the event, key : {} CqName: {}, Error: {}",
                  new Object[] {((EntryEvent) event).getKey(), cQuery.getName(),
                      ex.getLocalizedMessage()});
            }

            if (error) {
              cqEvent = MESSAGE_TYPE_EXCEPTION;
            } else {
              if (b_cqResults_newValue) {
                if (b_cqResults_oldValue) {
                  cqEvent = MESSAGE_TYPE_LOCAL_UPDATE;
                } else {
                  cqEvent = MESSAGE_TYPE_LOCAL_CREATE;
                }
                // If its create and caching is enabled, cache the key
                // for this CQ.
                cQuery.addToCqResultKeys(eventKey);
              } else if (b_cqResults_oldValue) {
                // Base invalidate operation is treated as destroy.
                // When the invalidate comes through, the entry will no longer
                // satisfy the query and will need to be deleted.
                cqEvent = MESSAGE_TYPE_LOCAL_DESTROY;
                // If caching is enabled, mark this event's key as removed
                // from the CQ cache.
                cQuery.markAsDestroyedInCqResultKeys(eventKey);
              }
            }
          }

          // Get the matching CQs if any.
          // synchronized (this.matchingCqMap){
          String query = cQuery.getQueryString();
          Set matchingCqs = matchingCqMap.get(query);
          if (matchingCqs != null) {
            for (Object matchingCq : matchingCqs) {
              String matchingCqName = (String) matchingCq;
              if (!matchingCqName.equals(cqName)) {
                matchedCqs.put(matchingCqName, cqEvent);
                if (isDebugEnabled) {
                  logger.debug("Adding CQ into Matching CQ Map: {} Event is: {}", matchingCqName,
                      cqEvent);
                }
              }
            }
          }
        }

        if (cqEvent != null && cQuery.isRunning()) {
          if (isDebugEnabled) {
            logger.debug("Added event to CQ with client-side name: {} key: {} operation : {}",
                cQuery.cqName, eventKey, cqEvent);
          }
          cqInfo.put(filterID, cqEvent);
          CqQueryVsdStats stats = cQuery.getVsdStats();
          if (stats != null) {
            stats.updateStats(cqEvent);
          }
        }
      }
      if (cqInfo.size() > 0) {
        if (pf.isLocalProfile()) {
          if (isDebugEnabled) {
            logger.debug("Setting local CQ matches to {}", cqInfo);
          }
          frInfo.setLocalCqInfo(cqInfo);
        } else {
          if (isDebugEnabled) {
            logger.debug("Setting CQ matches for {} to {}", cf.getDistributedMember(), cqInfo);
          }
          frInfo.setCqRoutingInfo(cf.getDistributedMember(), cqInfo);
        }
      }
    } // iteration over Profiles.
  }

  private Integer generateCqRegionEvent(CacheEvent event) {
    Integer cqEvent = null;
    if (event.getOperation().isRegionDestroy()) {
      cqEvent = MessageType.DESTROY_REGION;
    } else if (event.getOperation().isRegionInvalidate()) {
      cqEvent = MessageType.INVALIDATE_REGION;
    } else if (event.getOperation().isClear()) {
      cqEvent = MessageType.CLEAR_REGION;
    }
    return cqEvent;
  }

  /**
   * Manages the CQs created for the base region. This is managed here, instead of on the base
   * region; since the cq could be created on the base region, before base region is created (using
   * newCq()).
   */
  private void addToBaseRegionToCqNameMap(String regionName, String cqName) {
    synchronized (baseRegionToCqNameMap) {
      ArrayList<String> cqs = baseRegionToCqNameMap.get(regionName);
      if (cqs == null) {
        cqs = new ArrayList<>();
      }
      cqs.add(cqName);
      baseRegionToCqNameMap.put(regionName, cqs);
    }
  }

  void removeFromBaseRegionToCqNameMap(String regionName, String cqName) {
    synchronized (baseRegionToCqNameMap) {
      ArrayList<String> cqs = baseRegionToCqNameMap.get(regionName);
      if (cqs != null) {
        cqs.remove(cqName);
        if (cqs.isEmpty()) {
          baseRegionToCqNameMap.remove(regionName);
        } else {
          baseRegionToCqNameMap.put(regionName, cqs);
        }
      }
    }
  }

  /**
   * Get the VSD ststs for CQ Service. There is one CQ Service per cache
   *
   * @return reference to VSD stats object for the CQ service
   */
  public CqServiceVsdStats getCqServiceVsdStats() {
    return stats;
  }

  /**
   * Adds the query from the given CQ to the matched CQ map.
   */
  void addToMatchingCqMap(CqQueryImpl cq) {
    synchronized (matchingCqMap) {
      String cqQuery = cq.getQueryString();
      Set<String> matchingCQs;
      if (!matchingCqMap.containsKey(cqQuery)) {
        matchingCQs = Collections.newSetFromMap(new ConcurrentHashMap<>());
        matchingCqMap.put(cqQuery, matchingCQs);
        stats.incUniqueCqQuery();
      } else {
        matchingCQs = matchingCqMap.get(cqQuery);
      }
      matchingCQs.add(cq.getServerCqName());
      if (logger.isDebugEnabled()) {
        logger.debug("Adding CQ into MatchingCQ map, CQName: {} Number of matched querys are: {}",
            cq.getServerCqName(), matchingCQs.size());
      }
    }
  }

  /**
   * Removes the query from the given CQ from the matched CQ map.
   */
  private void removeFromMatchingCqMap(CqQueryImpl cq) {
    synchronized (matchingCqMap) {
      String cqQuery = cq.getQueryString();
      if (matchingCqMap.containsKey(cqQuery)) {
        Set matchingCQs = matchingCqMap.get(cqQuery);
        matchingCQs.remove(cq.getServerCqName());
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Removing CQ from MatchingCQ map, CQName: {} Number of matched querys are: {}",
              cq.getServerCqName(), matchingCQs.size());
        }
        if (matchingCQs.isEmpty()) {
          matchingCqMap.remove(cqQuery);
          stats.decUniqueCqQuery();
        }
      }
    }
  }

  /**
   * Returns the matching CQ map.
   *
   * @return HashMap matchingCqMap
   */
  public Map<String, Set<String>> getMatchingCqMap() {
    return matchingCqMap;
  }

  /**
   * Applies the query on the event. This method takes care of the performance related changed done
   * to improve the CQ-query performance. When CQ-query is executed first time, it saves the query
   * related information in the execution context and uses that info in later executions.
   */
  private boolean evaluateQuery(CqQueryImpl cQuery, Object[] event) throws Exception {
    ExecutionContext execContext = cQuery.getQueryExecutionContext();
    execContext.reset();
    execContext.setBindArguments(event);
    boolean status = false;

    // Check if the CQ query is executed once.
    // If not execute the query in normal way.
    // During this phase the query execution related info are stored in the
    // ExecutionContext.
    if (execContext.getScopeNum() <= 0) {
      SelectResults results =
          (SelectResults) ((DefaultQuery) cQuery.getQuery()).executeUsingContext(execContext);
      if (results != null && results.size() > 0) {
        status = true;
      }
    } else {
      // Execute using the saved query info (in ExecutionContext).
      // This avoids building resultSet, index look-up, generating build-plans
      // that are not required for; query execution on single object.
      CompiledSelect cs = ((DefaultQuery) (cQuery.getQuery())).getSelect();
      status = cs.evaluateCq(execContext);
    }
    return status;
  }

  @Override
  public UserAttributes getUserAttributes(String cqName) {
    return cqNameToUserAttributesMap.get(cqName);
  }

  @Override
  public void cqsDisconnected(Pool pool) {
    invokeCqsConnected(pool, false);
  }

  @Override
  public void cqsConnected(Pool pool) {
    invokeCqsConnected(pool, true);
  }

  /**
   * Let cq listeners know that they are connected or disconnected
   */
  private void invokeCqsConnected(Pool pool, boolean connected) {
    String poolName = pool.getName();
    // Check to see if we are already connected/disconnected.
    // If state has not changed, do not invoke another connected/disconnected
    synchronized (cqPoolsConnected) {
      // don't repeatedly send same connect/disconnect message to cq's on repeated fails of
      // RedundancySatisfier
      if (cqPoolsConnected.containsKey(poolName) && connected == cqPoolsConnected.get(poolName)) {
        return;
      }
      cqPoolsConnected.put(poolName, connected);

      Collection<? extends InternalCqQuery> cqs = getAllCqs();
      String cqName;
      final boolean isDebugEnabled = logger.isDebugEnabled();
      for (InternalCqQuery query : cqs) {
        try {
          if (query == null) {
            continue;
          }

          cqName = query.getName();
          ClientCQImpl cQuery = (ClientCQImpl) getCq(cqName);

          // Check cq pool to determine if the pool matches, if not continue.
          // Also if the connected state is already the same, we do not have to send status again.
          if (cQuery == null || cQuery.getCQProxy() == null) {
            continue;
          }
          Pool cqPool = cQuery.getCQProxy().getPool();
          if (cQuery.isConnected() == connected || !cqPool.getName().equals(poolName)) {
            continue;
          }

          if ((!cQuery.isRunning() && cQuery.getQueuedEvents() == null)) {
            if (isDebugEnabled) {
              logger.debug("Unable to invoke CqListener, CQ is Not running, CqName : {}", cqName);
            }
            continue;
          }

          invokeCqConnectedListeners(cqName, cQuery, connected);
        } catch (VirtualMachineError e) {
          SystemFailure.initiateFailure(e);
          throw e;
        } catch (Throwable t) {
          SystemFailure.checkFailure();
          logger.warn("Error while sending connection status to cq listeners", t);
        }
      }
    }
  }

  @Override
  public List<String> getAllDurableCqsFromServer(InternalPool pool) {
    return new ServerCQProxyImpl(pool).getAllDurableCqsFromServer();
  }
}
