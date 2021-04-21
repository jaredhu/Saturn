/**
 * Copyright 2016 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * </p>
 **/

package com.vip.saturn.job.executor;

import com.vip.saturn.job.threads.SaturnThreadFactory;
import com.vip.saturn.job.utils.LogUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.vip.saturn.job.utils.LogEvents.ExecutorEvent.COMMON;

/**
 * @author hebelala
 */
public abstract class EnhancedConnectionStateListener implements ConnectionStateListener {

	private static final Logger log = LoggerFactory.getLogger(EnhancedConnectionStateListener.class);

    private String executorName;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private ExecutorService checkLostThread;

    public EnhancedConnectionStateListener(String executorName) {
        this.executorName = executorName;
        this.checkLostThread = Executors
                .newSingleThreadExecutor(new SaturnThreadFactory(executorName + "-check-lost-thread", false));
    }

    private long getSessionId(CuratorFramework client) {
        long sessionId;
        try {
            sessionId = client.getZookeeperClient().getZooKeeper().getSessionId();
        } catch (Exception e) {// NOSONAR
            return -1;
        }
        return sessionId;
    }

    @Override
    public void stateChanged(final CuratorFramework client, final ConnectionState newState) {
        if (closed) {
            return;
        }
        final String clientStr = client.toString();
        if (ConnectionState.SUSPENDED == newState) {
            connected = false;
			LogUtils.warn(log, COMMON,
					"The executor {} found zk is SUSPENDED, client is {}", executorName, clientStr);
            final long sessionId = getSessionId(client);
            if (!closed) {
                checkLostThread.submit(new Runnable() {
                    @Override
                    public void run() {
                        do {
                            try {
                                Thread.sleep(1000L);
                            } catch (InterruptedException e) {
								LogUtils.debug(log, EnhancedConnectionStateListener.class.getCanonicalName(),
										"checkLostThread is interrupted");
                            }
                            if (closed) {
                                break;
                            }
                            long newSessionId = getSessionId(client);
                            if (sessionId != newSessionId) {
								LogUtils.warn(log, EnhancedConnectionStateListener.class.getCanonicalName(),
										"The executor {} is going to restart for zk lost, client is {}", executorName,
										clientStr);

                                onLost();
                                break;
                            }
                        } while (!closed && !connected);
                    }
                });
            }
        } else if (ConnectionState.RECONNECTED == newState) {
			LogUtils.warn(log, COMMON,
					"The executor {} found zk is RECONNECTED, client is {}", executorName, clientStr);
            connected = true;
        }
    }

    public abstract void onLost();

    public void close() {
        this.closed = true;
        this.checkLostThread.shutdownNow();
    }

}