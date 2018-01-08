/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreDescriptor;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class used for interact with a ZK term node. <br/>
 * Each ZK term node relates to a shard of a collection and have this format (in json) <br/>
 * <code>
 * {<br />
 *   "replicaNodeName1" : 1,<br />
 *   "replicaNodeName2" : 2,<br />
 *   ..<br />
 * }<br />
 * </code>
 * The values correspond to replicas are called terms.
 * Only replicas with highest term value are considered up to date and be able to become leader and serve queries.
 * <br/>
 * Terms can only updated in two strict ways: <br/>
 * - A replica sets its term equals to leader's term <br/>
 * - The leader increase its term and some other replicas by 1 <br/>
 */
public class ZkShardTerms implements AutoCloseable{

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Object writingLock = new Object();
  private final AtomicInteger numWatcher = new AtomicInteger(0);
  private final String collection;
  private final String shard;
  private final String znodePath;
  private final SolrZkClient zkClient;
  private final Set<CoreTermWatcher> listeners = new HashSet<>();

  private Terms terms;

  // Listener of a core for shard's term change events
  interface CoreTermWatcher {
    // return true if the listener wanna to be triggered in the next time
    boolean onTermChanged(Terms terms);
  }

  public ZkShardTerms(String collection, String shard, SolrZkClient zkClient) {
    this.znodePath = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/terms/" + shard;
    this.collection = collection;
    this.shard = shard;
    this.zkClient = zkClient;
    ensureTermNodeExist();
    refreshTerms();
    ObjectReleaseTracker.track(this);
  }

  /**
   * Ensure that leader's term is lower than some replica's terms
   * @param leader coreNodeName of leader
   * @param replicasInLowerTerms replicas which should their term should be lower than leader's term
   * @return
   */
  public void ensureTermsIsHigher(String leader, Set<String> replicasInLowerTerms) {
    Terms newTerms;
    while( (newTerms = terms.increaseTerms(leader, replicasInLowerTerms)) != null) {
      if (forceSaveTerms(newTerms)) return;
    }
  }

  /**
   * Can this replica become leader or is this replica's term equals to leader's term?
   * @param coreNodeName of the replica
   * @return true if this replica can become leader, false if otherwise
   */
  public boolean canBecomeLeader(String coreNodeName) {
    return terms.canBecomeLeader(coreNodeName);
  }

  /**
   * Did this replica registered its term? This is a sign to check f
   * @param coreNodeName of the replica
   * @return true if this replica registered its term, false if otherwise
   */
  public boolean registered(String coreNodeName) {
    return terms.getTerm(coreNodeName) != null;
  }

  public void close() {
    // no watcher will be registered
    numWatcher.addAndGet(1);
    ObjectReleaseTracker.release(this);
  }

  // package private for testing, only used by tests
  Map<String, Long> getTerms() {
    synchronized (writingLock) {
      return new HashMap<>(terms.values);
    }
  }

  /**
   * Add a listener so the next time the shard's term get updated, listeners will be called
   */
  void addListener(CoreTermWatcher listener) {
    synchronized (listeners) {
      listeners.add(listener);
    }
  }

  /**
   * Remove the coreNodeName from terms map and also remove any expired listeners
   * @return Return true if this object should not be reused
   */
  boolean removeTerm(CoreDescriptor cd) {
    int numListeners;
    synchronized (listeners) {
      // solrcore already closed
      listeners.removeIf(coreTermWatcher -> !coreTermWatcher.onTermChanged(terms));
      numListeners = listeners.size();
    }
    Terms newTerms;
    while ( (newTerms = terms.removeTerm(cd.getCloudDescriptor().getCoreNodeName())) != null) {
      try {
        if (saveTerms(newTerms)) return numListeners == 0;
      } catch (KeeperException.NoNodeException e) {
        return true;
      }
    }
    return true;
  }

  /**
   * Register a repilca's term (term value will be 0).
   * If a term is already associate with this replica do nothing
   * @param coreNodeName of the replica
   */
  void registerTerm(String coreNodeName) {
    Terms newTerms;
    while ( (newTerms = terms.registerTerm(coreNodeName)) != null) {
      if (forceSaveTerms(newTerms)) break;
    }
  }

  /**
   * Set a replica's term equals to leader's term
   * @param coreNodeName of the replica
   */
  void setEqualsToMax(String coreNodeName) {
    Terms newTerms;
    while ( (newTerms = terms.setEqualsToMax(coreNodeName)) != null) {
      if (forceSaveTerms(newTerms)) break;
    }
  }

  // package private for testing, only used by tests
  int getNumListeners() {
    synchronized (listeners) {
      return listeners.size();
    }
  }

  /**
   * Set new terms to ZK.
   * In case of correspond ZK term node is not created, create it
   * @param newTerms to be set
   * @return true if terms is saved successfully to ZK, false if otherwise
   */
  private boolean forceSaveTerms(Terms newTerms) {
    try {
      return saveTerms(newTerms);
    } catch (KeeperException.NoNodeException e) {
      ensureTermNodeExist();
      return false;
    }
  }

  /**
   * Set new terms to ZK, the version of new terms must match the current ZK term node
   * @param newTerms to be set
   * @return true if terms is saved successfully to ZK, false if otherwise
   * @throws KeeperException.NoNodeException correspond ZK term node is not created
   */
  private boolean saveTerms(Terms newTerms) throws KeeperException.NoNodeException {
    byte[] znodeData = Utils.toJSON(newTerms.values);
    try {
      Stat stat = zkClient.setData(znodePath, znodeData, newTerms.version, true);
      setNewTerms(new Terms(newTerms.values, stat.getVersion()));
      return true;
    } catch (KeeperException.BadVersionException e) {
      log.info("Failed to save terms, version is not match, retrying");
      refreshTerms();
    } catch (KeeperException.NoNodeException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error save shard term for collection:" + collection, e);
    }
    return false;
  }

  /**
   * Create correspond ZK term node
   */
  private void ensureTermNodeExist() {
    String path = "/collections/"+collection+ "/terms";
    try {
      if (!zkClient.exists(path, true)) {
        try {
          zkClient.makePath(path, true);
        } catch (KeeperException.NodeExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
      path += "/"+shard;
      if (!zkClient.exists(path, true)) {
        try {
          Map<String, Long> initialTerms = new HashMap<>();
          zkClient.create(path, Utils.toJSON(initialTerms), CreateMode.PERSISTENT, true);
        } catch (KeeperException.NodeExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
    }  catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error creating shard term node in Zookeeper for collection:" + collection, e);
    } catch (KeeperException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error creating shard term node in Zookeeper for collection:" + collection, e);
    }
  }

  /**
   * Fetch the latest terms from ZK.
   * This method will atomically register a watcher to the correspond ZK term node,
   * so {@link ZkShardTerms#terms} will stay up to date.
   */
  private void refreshTerms() {
    try {
      Watcher watcher = null;
      if (numWatcher.compareAndSet(0, 1)) {
        watcher = event -> {
          numWatcher.decrementAndGet();
          refreshTerms();
        };
      }

      Stat stat = new Stat();
      byte[] data = zkClient.getData(znodePath, watcher, stat, true);
      Terms newTerms = new Terms((Map<String, Long>) Utils.fromJSON(data), stat.getVersion());
      setNewTerms(newTerms);
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error updating shard term for collection:" + collection, e);
    } catch (KeeperException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error updating shard term for collection:" + collection, e);
    }
  }

  /**
   * Atomically update {@link ZkShardTerms#terms} and call listeners
   * @param newTerms to be set
   */
  private void setNewTerms(Terms newTerms) {
    boolean isChanged = false;
    synchronized (writingLock) {
      if (terms == null || newTerms.version > terms.version) {
        terms = newTerms;
        isChanged = true;
      }
    }
    if (isChanged) onTermUpdates(newTerms);
  }

  private void onTermUpdates(Terms newTerms) {
    synchronized (listeners) {
      listeners.removeIf(coreTermWatcher -> !coreTermWatcher.onTermChanged(newTerms));
    }
  }

  /**
   * Hold values of terms, this class is immutable
   */
  static class Terms {
    private final Map<String, Long> values;
    // ZK node version
    private final int version;

    public Terms () {
      this(new HashMap<>(), 0);
    }

    public Terms(Map<String, Long> values, int version) {
      this.values = values;
      this.version = version;
    }

    boolean canBecomeLeader(String coreNodeName) {
      if (values.isEmpty()) return true;
      long maxTerm = Collections.max(values.values());
      return values.getOrDefault(coreNodeName, 0L) == maxTerm;
    }

    Long getTerm(String coreNodeName) {
      return values.get(coreNodeName);
    }

    Terms increaseTerms(String leader, Set<String> replicasInLowerTerms) {
      if (!values.containsKey(leader)) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Can not find leader's term " + leader);
      }

      boolean changed = false;
      boolean foundReplicasInLowerTerms = false;

      HashMap<String, Long> newValues = new HashMap<>(values);
      long leaderTerm = newValues.get(leader);
      for (String replica : newValues.keySet()) {
        if (replicasInLowerTerms.contains(replica)) foundReplicasInLowerTerms = true;
        if (Objects.equals(newValues.get(replica), leaderTerm)) {
          if(replicasInLowerTerms.contains(replica)) {
            changed = true;
          } else {
            newValues.put(replica, leaderTerm+1);
          }
        }
      }

      // We should skip the optimization if there are no replicasInLowerTerms present in local terms,
      // this may indicate that the current value is stale
      if (!changed && foundReplicasInLowerTerms) return null;
      return new Terms(newValues, version);
    }

    Terms removeTerm(String coreNodeName) {
      if (!values.containsKey(coreNodeName)) return null;

      HashMap<String, Long> newValues = new HashMap<>(values);
      newValues.remove(coreNodeName);
      return new Terms(newValues, version);
    }

    Terms registerTerm(String coreNodeName) {
      if (values.containsKey(coreNodeName)) return null;

      HashMap<String, Long> newValues = new HashMap<>(values);
      newValues.put(coreNodeName, 0L);
      return new Terms(newValues, version);
    }

    Terms setEqualsToMax(String coreNodeName) {
      long maxTerm;
      try {
        maxTerm = Collections.max(values.values());
      } catch (NoSuchElementException e){
        maxTerm = 0;
      }
      if (values.get(coreNodeName) == maxTerm) return null;

      HashMap<String, Long> newValues = new HashMap<>(values);
      newValues.put(coreNodeName, maxTerm);
      return new Terms(newValues, version);
    }
  }
}
