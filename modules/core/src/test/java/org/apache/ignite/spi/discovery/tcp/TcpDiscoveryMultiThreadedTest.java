/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.spi.discovery.tcp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteClientDisconnectedException;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.IgniteClientDisconnectedCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.client.util.GridConcurrentHashSet;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

import static org.apache.ignite.events.EventType.EVT_JOB_MAPPED;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.events.EventType.EVT_TASK_FAILED;
import static org.apache.ignite.events.EventType.EVT_TASK_FINISHED;

/**
 * Test for {@link TcpDiscoverySpi}.
 */
public class TcpDiscoveryMultiThreadedTest extends GridCommonAbstractTest {
    /** */
    private static final int GRID_CNT = 5;

    /** */
    private static final int CLIENT_GRID_CNT = 5;

    /** */
    private static final ThreadLocal<Boolean> clientFlagPerThread = new ThreadLocal<>();

    /** */
    private static final ThreadLocal<UUID> nodeId = new ThreadLocal<>();

    /** */
    private static volatile boolean clientFlagGlobal;

    /** */
    private static GridConcurrentHashSet<UUID> failedNodes = new GridConcurrentHashSet<>();

    /**
     * @return Client node flag.
     */
    private static boolean client() {
        Boolean client = clientFlagPerThread.get();

        return client != null ? client : clientFlagGlobal;
    }

    /** */
    private TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /**
     * @throws Exception If fails.
     */
    public TcpDiscoveryMultiThreadedTest() throws Exception {
        super(false);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional"})
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        UUID id = nodeId.get();

        if (id != null) {
            cfg.setNodeId(id);

            nodeId.set(null);
        }

        if (client())
            cfg.setClientMode(true);

        cfg.setDiscoverySpi(new TcpDiscoverySpi().
            setIpFinder(ipFinder).
            setJoinTimeout(60_000).
            setNetworkTimeout(10_000));

        int[] evts = {EVT_NODE_FAILED, EVT_NODE_LEFT};

        Map<IgnitePredicate<? extends Event>, int[]> lsnrs = new HashMap<>();

        lsnrs.put(new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                DiscoveryEvent discoveryEvt = (DiscoveryEvent)evt;

                failedNodes.add(discoveryEvt.eventNode().id());

                return true;
            }
        }, evts);

        cfg.setLocalEventListeners(lsnrs);

        cfg.setCacheConfiguration();

        cfg.setIncludeEventTypes(EVT_TASK_FAILED, EVT_TASK_FINISHED, EVT_JOB_MAPPED);

        cfg.setIncludeProperties();

        ((TcpCommunicationSpi)cfg.getCommunicationSpi()).setSharedMemoryPort(-1);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();

        failedNodes.clear();
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 5 * 60 * 1000;
    }

    /**
     * @throws Exception If any error occurs.
     */
    public void _testMultiThreadedClientsRestart() throws Exception {
        final AtomicBoolean done = new AtomicBoolean();

        try {
            clientFlagGlobal = false;

            info("Test timeout: " + (getTestTimeout() / (60 * 1000)) + " min.");

            startGridsMultiThreaded(GRID_CNT);

            clientFlagGlobal = true;

            startGridsMultiThreaded(GRID_CNT, CLIENT_GRID_CNT);

            final AtomicInteger clientIdx = new AtomicInteger(GRID_CNT);

            IgniteInternalFuture<?> fut1 = multithreadedAsync(
                new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        clientFlagPerThread.set(true);

                        int idx = clientIdx.getAndIncrement();

                        while (!done.get()) {
                            stopGrid(idx, true);
                            startGrid(idx);
                        }

                        return null;
                    }
                },
                CLIENT_GRID_CNT,
                "client-restart");

            Thread.sleep(getTestTimeout() - 60 * 1000);

            done.set(true);

            fut1.get();
        }
        finally {
            done.set(true);
        }
    }

    /**
     * @throws Exception If any error occurs.
     */
    public void testMultiThreadedClientsServersRestart() throws Throwable {
        final AtomicBoolean done = new AtomicBoolean();

        try {
            clientFlagGlobal = false;

            info("Test timeout: " + (getTestTimeout() / (60 * 1000)) + " min.");

            startGridsMultiThreaded(GRID_CNT);

            clientFlagGlobal = true;

            startGridsMultiThreaded(GRID_CNT, CLIENT_GRID_CNT);

            final AtomicReference<Throwable> error = new AtomicReference<>();

            final BlockingQueue<Integer> clientStopIdxs = new LinkedBlockingQueue<>();

            for (int i = GRID_CNT; i < GRID_CNT + CLIENT_GRID_CNT; i++)
                clientStopIdxs.add(i);

            final AtomicInteger clientStartIdx = new AtomicInteger(9000);

            IgniteInternalFuture<?> fut1 = multithreadedAsync(
                new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        try {
                            clientFlagPerThread.set(true);

                            while (!done.get() && error.get() == null) {
                                Integer stopIdx = clientStopIdxs.take();

                                log.info("Stop client: " + stopIdx);

                                stopGrid(stopIdx);

                                while (!done.get() && error.get() == null) {
                                    // Generate unique name to simplify debugging.
                                    int startIdx = clientStartIdx.getAndIncrement();

                                    log.info("Start client: " + startIdx);

                                    UUID id = UUID.randomUUID();

                                    nodeId.set(id);

                                    try {
                                        Ignite ignite = startGrid(startIdx);

                                        assertTrue(ignite.configuration().isClientMode());

                                        clientStopIdxs.add(startIdx);

                                        break;
                                    }
                                    catch (Exception e) {
                                        if (X.hasCause(e, ClusterTopologyCheckedException.class))
                                            log.info("Client failed to start: " + e);
                                        if (X.hasCause(e, IgniteClientDisconnectedCheckedException.class) ||
                                            X.hasCause(e, IgniteClientDisconnectedException.class))
                                            log.info("Client disconnected: " + e);
                                        else {
                                            if (failedNodes.contains(id) && X.hasCause(e, IgniteSpiException.class))
                                                log.info("Client failed: " + e);
                                            else
                                                throw e;
                                        }
                                    }
                                }
                            }
                        }
                        catch (Throwable e) {
                            log.error("Unexpected error: " + e, e);

                            error.compareAndSet(null, e);

                            return null;
                        }

                        return null;
                    }
                },
                CLIENT_GRID_CNT,
                "client-restart");

            final BlockingQueue<Integer> srvStopIdxs = new LinkedBlockingQueue<>();

            for (int i = 0; i < GRID_CNT; i++)
                srvStopIdxs.add(i);

            final AtomicInteger srvStartIdx = new AtomicInteger(GRID_CNT + CLIENT_GRID_CNT);

            IgniteInternalFuture<?> fut2 = multithreadedAsync(
                new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        try {
                            clientFlagPerThread.set(false);

                            while (!done.get() && error.get() == null) {
                                int stopIdx = srvStopIdxs.take();

                                log.info("Stop server: " + stopIdx);

                                stopGrid(stopIdx);

                                // Generate unique name to simplify debugging.
                                int startIdx = srvStartIdx.getAndIncrement();

                                log.info("Start server: " + startIdx);

                                Ignite ignite = startGrid(startIdx);

                                assertFalse(ignite.configuration().isClientMode());

                                srvStopIdxs.add(startIdx);
                            }
                        }
                        catch (Throwable e) {
                            log.error("Unexpected error: " + e, e);

                            error.compareAndSet(null, e);

                            return null;
                        }

                        return null;
                    }
                },
                GRID_CNT - 1,
                "server-restart");

            final long timeToExec = getTestTimeout() - 60_000;

            final long endTime = System.currentTimeMillis() + timeToExec;

            while (System.currentTimeMillis() < endTime) {
                Thread.sleep(3000);

                if (error.get() != null) {
                    Throwable err = error.get();

                    U.error(log, "Test failed: " + err.getMessage());

                    done.set(true);

                    fut1.cancel();
                    fut2.cancel();

                    throw err;
                }
            }

            log.info("Stop test.");

            done.set(true);

            fut1.get();
            fut2.get();
        }
        finally {
            done.set(true);
        }
    }

    /**
     * @throws Exception If any error occurs.
     */
    public void _testTopologyVersion() throws Exception {
        clientFlagGlobal = false;

        startGridsMultiThreaded(GRID_CNT);

        long prev = 0;

        for (Ignite g : G.allGrids()) {
            IgniteKernal kernal = (IgniteKernal)g;

            long ver = kernal.context().discovery().topologyVersion();

            info("Top ver: " + ver);

            if (prev == 0)
                prev = ver;
        }

        info("Test finished.");
    }

    /**
     * @throws Exception If any error occurs.
     */
    public void _testMultipleStartOnCoordinatorStop() throws Exception{
        for (int k = 0; k < 3; k++) {
            log.info("Iteration: " + k);

            clientFlagGlobal = false;

            final int START_NODES = 5;
            final int JOIN_NODES = 10;

            startGrids(START_NODES);

            final CyclicBarrier barrier = new CyclicBarrier(JOIN_NODES + 1);

            final AtomicInteger startIdx = new AtomicInteger(START_NODES);

            IgniteInternalFuture<?> fut = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    int idx = startIdx.getAndIncrement();

                    Thread.currentThread().setName("start-thread-" + idx);

                    barrier.await();

                    Ignite ignite = startGrid(idx);

                    assertFalse(ignite.configuration().isClientMode());

                    log.info("Started node: " + ignite.name());

                    return null;
                }
            }, JOIN_NODES, "start-thread");

            barrier.await();

            U.sleep(ThreadLocalRandom.current().nextInt(10, 100));

            for (int i = 0; i < START_NODES; i++)
                stopGrid(i);

            fut.get();

            stopAllGrids();
        }
    }
}
