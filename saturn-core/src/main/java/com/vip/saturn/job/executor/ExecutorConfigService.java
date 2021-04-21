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

package com.vip.saturn.job.executor;

import com.vip.saturn.job.exception.SaturnExecutorException;
import com.vip.saturn.job.utils.JsonUtils;
import com.vip.saturn.job.utils.LogEvents;
import com.vip.saturn.job.utils.LogUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutorConfigService {

	private static final Logger log = LoggerFactory.getLogger(ExecutorConfigService.class);

	private static final String EXECUTOR_CONFIG_PATH = "/$SaturnSelf/saturn-executor/config";

	private String executorName;

	private CuratorFramework curatorFramework;

	private Class executorConfigClass;

	private NodeCache nodeCache;

	private volatile Object executorConfig;

	public ExecutorConfigService(String executorName, CuratorFramework curatorFramework, Class executorConfigClass) {
		this.executorName = executorName;
		this.curatorFramework = curatorFramework;
		this.executorConfigClass = executorConfigClass;
	}

	public void start() throws Exception {
		validateAndInitExecutorConfig();
		nodeCache = new NodeCache(curatorFramework.usingNamespace(null), EXECUTOR_CONFIG_PATH);
		nodeCache.getListenable().addListener(new NodeCacheListener() {
			@Override
			public void nodeChanged() throws Exception {
				// Watch create, update event
				try {
					final ChildData currentData = nodeCache.getCurrentData();
					if (currentData == null) {
						return;
					}

					String configStr = null;
					byte[] data = currentData.getData();
					if (data != null && data.length > 0) {
						configStr = new String(data, "UTF-8");
					}

					LogUtils.info(log, LogEvents.ExecutorEvent.INIT,
							"The path {} created or updated event is received by {}, the data is {}",
							EXECUTOR_CONFIG_PATH, executorName, configStr);
					if (StringUtils.isBlank(configStr)) {
						executorConfig = executorConfigClass.newInstance();
					} else {
						executorConfig = JsonUtils.getGson().fromJson(configStr, executorConfigClass);
					}
				} catch (Throwable t) {
					LogUtils.error(log, LogEvents.ExecutorEvent.INIT, t.toString(), t);
				}
			}

		});
		nodeCache.start(false);
	}

	private void validateAndInitExecutorConfig() throws Exception {
		if (curatorFramework == null) {
			throw new SaturnExecutorException("curatorFramework cannot be null");
		}

		if (executorConfigClass == null) {
			throw new SaturnExecutorException("executorConfigClass cannot be null");
		}
		Object temp = executorConfigClass.newInstance();
		if (!(temp instanceof ExecutorConfig)) {
			throw new SaturnExecutorException(String.format("executorConfigClass should be %s or its child",
					ExecutorConfig.class.getCanonicalName()));
		}
		executorConfig = temp;
	}

	public void stop() {
		try {
			if (nodeCache != null) {
				nodeCache.close();
			}
		} catch (Exception e) {
			LogUtils.error(log, LogEvents.ExecutorEvent.INIT_OR_SHUTDOWN, e.toString(), e);
		}
	}

	public ExecutorConfig getExecutorConfig() {
		return (ExecutorConfig) executorConfig;
	}
}
