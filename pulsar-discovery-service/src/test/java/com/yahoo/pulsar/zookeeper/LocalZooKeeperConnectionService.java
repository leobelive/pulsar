/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.zookeeper;

import static com.yahoo.pulsar.discovery.service.DiscoveryService.LOADBALANCE_BROKERS_ROOT;

import java.io.IOException;

import org.apache.bookkeeper.util.ZkUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.MockZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.yahoo.pulsar.zookeeper.ZooKeeperSessionWatcher.ShutdownService;

/**
 * 
 * Test Mock LocalZooKeeperConnectionService
 *
 */
public class LocalZooKeeperConnectionService {
    private static final Logger LOG = LoggerFactory.getLogger(LocalZooKeeperConnectionService.class);

    private final ZooKeeperClientFactory zkClientFactory;
    private final String zkConnect;
    private final long zkSessionTimeoutMillis;

    private ZooKeeper localZooKeeper;
    private ZooKeeperSessionWatcher localZooKeeperSessionWatcher;

    public LocalZooKeeperConnectionService(ZooKeeperClientFactory zkClientFactory, String zkConnect,
            long zkSessionTimeoutMillis) {
        this.zkClientFactory = zkClientFactory;
        this.zkConnect = zkConnect;
        this.zkSessionTimeoutMillis = zkSessionTimeoutMillis;
    }

    public void start(ShutdownService shutdownService) throws IOException {
        // mock zk
        try {
            localZooKeeper = MockZooKeeper.newInstance();
            ZkUtils.createFullPathOptimistic(localZooKeeper, LOADBALANCE_BROKERS_ROOT, "test".getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            localZooKeeperSessionWatcher = new ZooKeeperSessionWatcher(localZooKeeper, zkSessionTimeoutMillis,
                    shutdownService);
            localZooKeeperSessionWatcher.start();
            localZooKeeper.register(localZooKeeperSessionWatcher);
        } catch (Exception e) {
            throw new IOException("Failed to establish session with local ZK", e);
        }
    }

    public void close() throws IOException {
        if (localZooKeeper != null) {
            try {
                localZooKeeper.close();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        if (localZooKeeperSessionWatcher != null) {
            localZooKeeperSessionWatcher.close();
        }
    }

    public ZooKeeper getLocalZooKeeper() {
        return this.localZooKeeper;
    }

    /**
     * Check if a persist node exists. If not, it attempts to create the znode.
     *
     * @param path
     *            znode path
     * @throws KeeperException
     *             zookeeper exception.
     * @throws InterruptedException
     *             zookeeper exception.
     */
    public static void checkAndCreatePersistNode(ZooKeeper zkc, String path)
            throws KeeperException, InterruptedException {

        // check if the node exists
        if (zkc.exists(path, false) == null) {
            /**
             * create znode
             */
            try {
                // do create the node
                zkc.create(path, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                LOG.info("created znode, path={}", path);
            } catch (Exception e) {
                LOG.warn("create znode failed, path={} : {}", path, e.getMessage(), e);
            }
        }
    }

    public static String createIfAbsent(ZooKeeper zk, String path, String data, CreateMode createMode)
            throws KeeperException, InterruptedException {
        return createIfAbsent(zk, path, data, createMode, false);
    }

    public static String createIfAbsent(ZooKeeper zk, String path, String data, CreateMode createMode, boolean gc)
            throws KeeperException, InterruptedException {
        return createIfAbsent(zk, path, data.getBytes(Charsets.UTF_8), createMode, gc);
    }

    public static String createIfAbsent(ZooKeeper zk, String path, byte[] data, CreateMode createMode)
            throws KeeperException, InterruptedException {
        return createIfAbsent(zk, path, data, createMode, false);
    }

    public static String createIfAbsent(ZooKeeper zk, String path, byte[] data, CreateMode createMode, boolean gc)
            throws KeeperException, InterruptedException {
        String pathCreated = null;
        try {
            pathCreated = zk.create(path, data, Ids.OPEN_ACL_UNSAFE, createMode);
        } catch (NodeExistsException e) {
            // OK
            LOG.debug("Create skipped for existing znode: path={}", path);
        }
        // reset if what exists is the ephemeral garbage.
        if (gc && (pathCreated == null) && CreateMode.EPHEMERAL.equals(createMode)) {
            Stat stat = zk.exists(path, false);
            if (stat != null && zk.getSessionId() != stat.getEphemeralOwner()) {
                deleteIfExists(zk, path, -1);
                pathCreated = zk.create(path, data, Ids.OPEN_ACL_UNSAFE, createMode);
            }
        }
        return pathCreated;
    }

    public static void deleteIfExists(ZooKeeper zk, String path, int version)
            throws KeeperException, InterruptedException {
        try {
            zk.delete(path, version);
        } catch (NoNodeException e) {
            // OK
            LOG.debug("Delete skipped for non-existing znode: path={}", path);
        }
    }
}
