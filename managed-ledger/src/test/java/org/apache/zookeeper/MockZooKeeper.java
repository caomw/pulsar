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
package org.apache.zookeeper;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.mledger.util.Pair;
import org.apache.zookeeper.AsyncCallback.Children2Callback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import io.netty.util.concurrent.DefaultThreadFactory;
import sun.reflect.ReflectionFactory;

@SuppressWarnings({ "deprecation", "restriction", "rawtypes" })
public class MockZooKeeper extends ZooKeeper {
    private TreeMap<String, Pair<String, Integer>> tree;
    private SetMultimap<String, Watcher> watchers;
    private boolean stopped;
    private boolean alwaysFail = false;

    private ExecutorService executor;

    private AtomicInteger stepsToFail;
    private KeeperException.Code failReturnCode;
    private Watcher sessionWatcher;
    private long sessionId = 0L;

    public static MockZooKeeper newInstance() {
        try {
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor objDef = Object.class.getDeclaredConstructor(new Class[0]);
            Constructor intConstr = rf.newConstructorForSerialization(MockZooKeeper.class, objDef);
            MockZooKeeper zk = MockZooKeeper.class.cast(intConstr.newInstance());
            zk.init();
            return zk;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create object", e);
        }
    }

    private void init() {
        tree = Maps.newTreeMap();
        executor = Executors.newFixedThreadPool(1, new DefaultThreadFactory("mock-zookeeper"));
        SetMultimap<String, Watcher> w = HashMultimap.create();
        watchers = Multimaps.synchronizedSetMultimap(w);
        stopped = false;
        stepsToFail = new AtomicInteger(-1);
        failReturnCode = KeeperException.Code.OK;
    }

    private MockZooKeeper(String quorum) throws Exception {
        // This constructor is never called
        super(quorum, 1, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
            }
        });
        assert false;
    }

    @Override
    public States getState() {
        return States.CONNECTED;
    }

    @Override
    public synchronized void register(Watcher watcher) {
        sessionWatcher = watcher;
    }

    @Override
    public synchronized String create(String path, byte[] data, List<ACL> acl, CreateMode createMode)
            throws KeeperException, InterruptedException {
        checkProgrammedFail();

        if (stopped)
            throw new KeeperException.ConnectionLossException();

        if (tree.containsKey(path)) {
            throw new KeeperException.NodeExistsException(path);
        }

        final String parent = path.substring(0, path.lastIndexOf("/"));
        if (!parent.isEmpty() && !tree.containsKey(parent)) {
            throw new KeeperException.NoNodeException();
        }

        if (createMode == CreateMode.EPHEMERAL_SEQUENTIAL || createMode == CreateMode.PERSISTENT_SEQUENTIAL) {
            String parentData = tree.get(parent).first;
            int parentVersion = tree.get(parent).second;
            path = path + parentVersion;

            // Update parent version
            tree.put(parent, Pair.create(parentData, parentVersion + 1));
        }

        tree.put(path, Pair.create(new String(data), 0));

        if (!parent.isEmpty()) {
            final Set<Watcher> toNotifyParent = Sets.newHashSet();
            toNotifyParent.addAll(watchers.get(parent));

            executor.execute(() -> {
                toNotifyParent.forEach(watcher -> watcher
                        .process(new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, parent)));
            });
        }

        return path;

    }

    @Override
    public synchronized void create(final String path, final byte[] data, final List<ACL> acl, CreateMode createMode,
            final StringCallback cb, final Object ctx) {
        if (stopped) {
            cb.processResult(KeeperException.Code.CONNECTIONLOSS.intValue(), path, ctx, null);
            return;
        }

        executor.execute(() -> {
            String parent = path.substring(0, path.lastIndexOf("/"));

            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null);
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.CONNECTIONLOSS.intValue(), path, ctx, null);
                } else if (tree.containsKey(path)) {
                    cb.processResult(KeeperException.Code.NODEEXISTS.intValue(), path, ctx, null);
                } else if (!parent.isEmpty() && !tree.containsKey(parent)) {
                    cb.processResult(KeeperException.Code.NONODE.intValue(), path, ctx, null);
                } else {
                    tree.put(path, Pair.create(new String(data), 0));
                    cb.processResult(0, path, ctx, null);
                    if (!parent.isEmpty()) {
                        watchers.get(parent).forEach(watcher -> watcher.process(
                                new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, parent)));
                    }
                }
            }
        });
    }

    @Override
    public synchronized byte[] getData(String path, Watcher watcher, Stat stat) throws KeeperException {
        checkProgrammedFail();

        Pair<String, Integer> value = tree.get(path);
        if (value == null) {
            throw new KeeperException.NoNodeException(path);
        } else {
            if (watcher != null) {
                watchers.put(path, watcher);
            }
            if (stat != null) {
                stat.setVersion(value.second);
            }
            return value.first.getBytes();
        }
    }

    @Override
    public void getData(final String path, boolean watch, final DataCallback cb, final Object ctx) {
        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null, null);
                    return;
                }

                Pair<String, Integer> value = tree.get(path);
                if (value == null) {
                    cb.processResult(KeeperException.Code.NoNode, path, ctx, null, null);
                } else {
                    Stat stat = new Stat();
                    stat.setVersion(value.second);
                    cb.processResult(0, path, ctx, value.first.getBytes(), stat);
                }
            }
        });
    }

    @Override
    public void getData(final String path, final Watcher watcher, final DataCallback cb, final Object ctx) {
        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.CONNECTIONLOSS.intValue(), path, ctx, null, null);
                    return;
                }

                Pair<String, Integer> value = tree.get(path);
                if (value == null) {
                    cb.processResult(KeeperException.Code.NONODE.intValue(), path, ctx, null, null);
                } else {
                    if (watcher != null) {
                        watchers.put(path, watcher);
                    }

                    Stat stat = new Stat();
                    stat.setVersion(value.second);
                    cb.processResult(0, path, ctx, value.first.getBytes(), stat);
                }
            }
        });
    }

    @Override
    public void getChildren(final String path, final Watcher watcher, final ChildrenCallback cb, final Object ctx) {
        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null);
                    return;
                }

                List<String> children = Lists.newArrayList();
                for (String item : tree.tailMap(path).keySet()) {
                    if (!item.startsWith(path)) {
                        break;
                    } else {
                        if (path.length() >= item.length()) {
                            continue;
                        }

                        String child = item.substring(path.length() + 1);
                        if (!child.contains("/")) {
                            children.add(child);
                        }
                    }
                }

                cb.processResult(0, path, ctx, children);
                if (watcher != null) {
                    watchers.put(path, watcher);
                }
            }
        });
    }

    @Override
    public synchronized List<String> getChildren(String path, Watcher watcher) throws KeeperException {
        checkProgrammedFail();

        if (!tree.containsKey(path)) {
            throw new KeeperException.NoNodeException();
        }

        List<String> children = Lists.newArrayList();
        for (String item : tree.tailMap(path).keySet()) {
            if (!item.startsWith(path)) {
                break;
            } else {
                if (path.length() >= item.length()) {
                    continue;
                }

                String child = item.substring(path.length() + 1);
                if (!child.contains("/")) {
                    children.add(child);
                }
            }
        }

        if (watcher != null) {
            watchers.put(path, watcher);
        }

        return children;
    }

    @Override
    public synchronized List<String> getChildren(String path, boolean watch)
            throws KeeperException, InterruptedException {
        checkProgrammedFail();

        if (stopped) {
            throw new KeeperException.ConnectionLossException();
        } else if (!tree.containsKey(path)) {
            throw new KeeperException.NoNodeException();
        }

        List<String> children = Lists.newArrayList();
        for (String item : tree.tailMap(path).keySet()) {
            if (!item.startsWith(path)) {
                break;
            } else {
                if (path.length() >= item.length()) {
                    continue;
                }

                String child = item.substring(path.length() + 1);
                if (!child.contains("/")) {
                    children.add(child);
                }
            }
        }
        return children;
    }

    @Override
    public void getChildren(final String path, boolean watcher, final Children2Callback cb, final Object ctx) {
        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null, null);
                    return;
                } else if (!tree.containsKey(path)) {
                    cb.processResult(KeeperException.Code.NoNode, path, ctx, null, null);
                    return;
                }

                log.debug("getChildren path={}", path);
                List<String> children = Lists.newArrayList();
                for (String item : tree.tailMap(path).keySet()) {
                    log.debug("Checking path {}", item);
                    if (!item.startsWith(path)) {
                        break;
                    } else if (item.equals(path)) {
                        continue;
                    } else {
                        String child = item.substring(path.length() + 1);
                        log.debug("child: '{}'", child);
                        if (!child.contains("/")) {
                            children.add(child);
                        }
                    }
                }

                log.debug("getChildren done path={} result={}", path, children);
                cb.processResult(0, path, ctx, children, new Stat());
            }
        });
    }

    @Override
    public synchronized Stat exists(String path, boolean watch) throws KeeperException, InterruptedException {
        checkProgrammedFail();

        if (stopped)
            throw new KeeperException.ConnectionLossException();

        if (tree.containsKey(path)) {
            Stat stat = new Stat();
            stat.setVersion(tree.get(path).second);
            return stat;
        } else {
            return null;
        }
    }

    @Override
    public synchronized Stat exists(String path, Watcher watcher) throws KeeperException, InterruptedException {
        checkProgrammedFail();

        if (stopped)
            throw new KeeperException.ConnectionLossException();

        if (watcher != null) {
            watchers.put(path, watcher);
        }

        if (tree.containsKey(path)) {
            Stat stat = new Stat();
            stat.setVersion(tree.get(path).second);
            return stat;
        } else {
            return null;
        }
    }

    public void exists(String path, boolean watch, StatCallback cb, Object ctx) {
        executor.execute(() -> {
            synchronized (this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null);
                    return;
                }

                if (tree.containsKey(path)) {
                    cb.processResult(0, path, ctx, new Stat());
                } else {
                    cb.processResult(KeeperException.Code.NoNode, path, ctx, null);
                }
            }
        });
    }

    @Override
    public void sync(String path, VoidCallback cb, Object ctx) {
        executor.execute(() -> {
            synchronized (this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx);
                    return;
                }

                cb.processResult(0, path, ctx);
            }
        });

    }

    @Override
    public synchronized Stat setData(final String path, byte[] data, int version)
            throws KeeperException, InterruptedException {
        checkProgrammedFail();

        if (stopped) {
            throw new KeeperException.ConnectionLossException();
        }

        if (!tree.containsKey(path)) {
            throw new KeeperException.NoNodeException();
        }

        int currentVersion = tree.get(path).second;

        // Check version
        if (version != -1 && version != currentVersion) {
            throw new KeeperException.BadVersionException(path);
        }

        int newVersion = currentVersion + 1;
        log.debug("[{}] Updating -- current version: {}", path, currentVersion);
        tree.put(path, Pair.create(new String(data), newVersion));

        final Set<Watcher> toNotify = Sets.newHashSet();
        toNotify.addAll(watchers.get(path));
        watchers.removeAll(path);

        executor.execute(() -> {
            toNotify.forEach(watcher -> watcher
                    .process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, path)));
        });

        Stat stat = new Stat();
        stat.setVersion(newVersion);
        return stat;
    }

    @Override
    public synchronized void setData(final String path, final byte[] data, int version, final StatCallback cb,
            final Object ctx) {
        if (stopped) {
            cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null);
            return;
        }

        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (getProgrammedFailStatus()) {
                    cb.processResult(failReturnCode.intValue(), path, ctx, null);
                    return;
                } else if (stopped) {
                    cb.processResult(KeeperException.Code.ConnectionLoss, path, ctx, null);
                    return;
                }

                if (!tree.containsKey(path)) {
                    cb.processResult(KeeperException.Code.NoNode, path, ctx, null);
                    return;
                }

                int currentVersion = tree.get(path).second;

                // Check version
                if (version != -1 && version != currentVersion) {
                    log.debug("[{}] Current version: {} -- Expected: {}", path, currentVersion, version);
                    cb.processResult(KeeperException.Code.BadVersion, path, ctx, null);
                    return;
                }

                int newVersion = currentVersion + 1;
                log.debug("[{}] Updating -- current version: {}", path, currentVersion);
                tree.put(path, Pair.create(new String(data), newVersion));
                Stat stat = new Stat();
                stat.setVersion(newVersion);
                cb.processResult(0, path, ctx, stat);

                for (Watcher watcher : watchers.get(path)) {
                    watcher.process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, path));
                }

                watchers.removeAll(path);
            }
        });
    }

    @Override
    public synchronized void delete(final String path, int version) throws InterruptedException, KeeperException {
        checkProgrammedFail();

        if (stopped) {
            throw new KeeperException.ConnectionLossException();
        } else if (!tree.containsKey(path)) {
            throw new KeeperException.NoNodeException(path);
        } else if (hasChildren(path)) {
            throw new KeeperException.NotEmptyException(path);
        }

        if (version != -1) {
            int currentVersion = tree.get(path).second;
            if (version != currentVersion) {
                throw new KeeperException.BadVersionException(path);
            }
        }

        tree.remove(path);

        final Set<Watcher> toNotifyDelete = Sets.newHashSet();
        toNotifyDelete.addAll(watchers.get(path));

        final Set<Watcher> toNotifyParent = Sets.newHashSet();
        final String parent = path.substring(0, path.lastIndexOf("/"));
        if (!parent.isEmpty()) {
            toNotifyParent.addAll(watchers.get(parent));
        }

        executor.execute(() -> {
            synchronized (MockZooKeeper.this) {
                if (stopped) {
                    return;
                }

                for (Watcher watcher1 : toNotifyDelete) {
                    watcher1.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
                }
                for (Watcher watcher2 : toNotifyParent) {
                    watcher2.process(
                            new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, parent));
                }
            }
        });

        watchers.removeAll(path);
    }

    @Override
    public synchronized void delete(final String path, int version, final VoidCallback cb, final Object ctx) {
        if (executor.isShutdown()) {
            cb.processResult(KeeperException.Code.SESSIONEXPIRED.intValue(), path, ctx);
            return;
        }

        final Set<Watcher> toNotifyDelete = Sets.newHashSet();
        toNotifyDelete.addAll(watchers.get(path));

        final Set<Watcher> toNotifyParent = Sets.newHashSet();
        final String parent = path.substring(0, path.lastIndexOf("/"));
        if (!parent.isEmpty()) {
            toNotifyParent.addAll(watchers.get(parent));
        }

        executor.execute(() -> {
            if (getProgrammedFailStatus()) {
                cb.processResult(failReturnCode.intValue(), path, ctx);
            } else if (stopped) {
                cb.processResult(KeeperException.Code.CONNECTIONLOSS.intValue(), path, ctx);
            } else if (!tree.containsKey(path)) {
                cb.processResult(KeeperException.Code.NONODE.intValue(), path, ctx);
            } else if (hasChildren(path)) {
                cb.processResult(KeeperException.Code.NOTEMPTY.intValue(), path, ctx);
            } else {
                if (version != -1) {
                    int currentVersion = tree.get(path).second;
                    if (version != currentVersion) {
                        cb.processResult(KeeperException.Code.BADVERSION.intValue(), path, ctx);
                        return;
                    }
                }

                tree.remove(path);
                cb.processResult(0, path, ctx);

                toNotifyDelete.forEach(watcher -> watcher
                        .process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path)));
                toNotifyParent.forEach(watcher -> watcher
                        .process(new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, parent)));
            }
        });

        watchers.removeAll(path);
    }

    @Override
    public void close() throws InterruptedException {
    }

    public synchronized void shutdown() throws InterruptedException {
        stopped = true;
        tree.clear();
        watchers.clear();
        executor.shutdownNow();
    }

    void checkProgrammedFail() throws KeeperException {
        if (stepsToFail.getAndDecrement() == 0 || this.alwaysFail) {
            throw KeeperException.create(failReturnCode);
        }
    }

    boolean getProgrammedFailStatus() {
        return stepsToFail.getAndDecrement() == 0;
    }

    public void failNow(KeeperException.Code rc) {
        failAfter(0, rc);
    }

    public void setAlwaysFail(KeeperException.Code rc) {
        this.alwaysFail = true;
        this.failReturnCode = rc;
    }

    public void unsetAlwaysFail() {
        this.alwaysFail = false;
    }

    public void failAfter(int steps, KeeperException.Code rc) {
        stepsToFail.set(steps);
        failReturnCode = rc;
    }

    public void setSessionId(long id) {
        sessionId = id;
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    private boolean hasChildren(String path) {
        return !tree.subMap(path + '/', path + '0').isEmpty();
    }

    @Override
    public String toString() {
        return "MockZookeeper";
    }

    private static final Logger log = LoggerFactory.getLogger(MockZooKeeper.class);
}
