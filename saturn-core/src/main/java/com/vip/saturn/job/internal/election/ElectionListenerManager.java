/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.saturn.job.internal.election;

import com.vip.saturn.job.utils.LogUtils;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vip.saturn.job.basic.JobScheduler;
import com.vip.saturn.job.internal.listener.AbstractListenerManager;
import com.vip.saturn.job.internal.storage.JobNodePath;

/**
 * 主节点选举监听管理器.
 *
 *
 */
public class ElectionListenerManager extends AbstractListenerManager {

	static Logger log = LoggerFactory.getLogger(ElectionListenerManager.class);

	private final LeaderElectionService leaderElectionService;

	private boolean isShutdown;

	public ElectionListenerManager(final JobScheduler jobScheduler) {
		super(jobScheduler);
		leaderElectionService = new LeaderElectionService(jobScheduler);
	}

	@Override
	public void start() {
		zkCacheManager.addNodeCacheListener(new LeaderElectionJobListener(),
				JobNodePath.getNodeFullPath(jobName, ElectionNode.LEADER_HOST));
	}

	@Override
	public void shutdown() {
		isShutdown = true;
		leaderElectionService.shutdown();
		zkCacheManager.closeNodeCache(JobNodePath.getNodeFullPath(jobName, ElectionNode.LEADER_HOST));
	}

	class LeaderElectionJobListener implements NodeCacheListener {

		@Override
		public void nodeChanged() throws Exception {
			zkCacheManager.getExecutorService().execute(new Runnable() {
				@Override
				public void run() {
					try {
						LogUtils.debug(log, jobName, "Leader host nodeChanged", jobName);
						if (isShutdown) {
							LogUtils.debug(log, jobName, "ElectionListenerManager has been shutdown");
							return;
						}
						if (!leaderElectionService.hasLeader()) {
							LogUtils.info(log, jobName, "Leader crashed, elect a new leader now");
							leaderElectionService.leaderElection();
							LogUtils.info(log, jobName, "Leader election completed");
						} else {
							LogUtils.debug(log, jobName, "Leader is already existing, unnecessary to election");
						}
					} catch (Throwable t) {
						LogUtils.error(log, jobName, t.getMessage(), t);
					}
				}
			});
		}
	}
}
