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

package com.vip.saturn.job.java;

import com.vip.saturn.job.threads.SaturnThreadFactory;
import com.vip.saturn.job.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class TimeoutSchedulerExecutor {

	private static Logger log = LoggerFactory.getLogger(TimeoutSchedulerExecutor.class);

	private static ConcurrentHashMap<String, ScheduledThreadPoolExecutor> scheduledThreadPoolExecutorMap = new ConcurrentHashMap<>();

	private TimeoutSchedulerExecutor() {

	}

	public static synchronized ScheduledThreadPoolExecutor createScheduler(String executorName) {
		if (!scheduledThreadPoolExecutorMap.containsKey(executorName)) {
			ScheduledThreadPoolExecutor timeoutExecutor = new ScheduledThreadPoolExecutor(
					Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
					new SaturnThreadFactory(executorName + "-timeout-watchdog", false));
			timeoutExecutor.setRemoveOnCancelPolicy(true);
			scheduledThreadPoolExecutorMap.put(executorName, timeoutExecutor);
			return timeoutExecutor;
		}
		return scheduledThreadPoolExecutorMap.get(executorName);
	}

	private static ScheduledThreadPoolExecutor getScheduler(String executorName) {
		return scheduledThreadPoolExecutorMap.get(executorName);
	}

	public static final void shutdownScheduler(String executorName) {
		if (getScheduler(executorName) != null) {
			getScheduler(executorName).shutdown();
			scheduledThreadPoolExecutorMap.remove(executorName);
		}
	}

	// Note that before running this method, method createScheduler() should have been run, so that
	// getScheduler(executorName) will not be null.
	public static final void scheduleTimeoutJob(String executorName, int timeoutSeconds,
			ShardingItemFutureTask shardingItemFutureTask) {
		ScheduledFuture<?> timeoutFuture = getScheduler(executorName)
				.schedule(new TimeoutHandleTask(shardingItemFutureTask), timeoutSeconds, TimeUnit.SECONDS);
		shardingItemFutureTask.setTimeoutFuture(timeoutFuture);
	}

	private static class TimeoutHandleTask implements Runnable {

		private ShardingItemFutureTask shardingItemFutureTask;

		public TimeoutHandleTask(ShardingItemFutureTask shardingItemFutureTask) {
			this.shardingItemFutureTask = shardingItemFutureTask;
		}

		@Override
		public void run() {
			try {
				JavaShardingItemCallable javaShardingItemCallable = shardingItemFutureTask.getCallable();
				if (!shardingItemFutureTask.isDone() && javaShardingItemCallable.setTimeout()) {
					String jobName = javaShardingItemCallable.getJobName();
					Integer item = javaShardingItemCallable.getItem();
					LogUtils.info(log, jobName, "Force stop timeout job, jobName:{}, item:{}", jobName, item);
					// 调用beforeTimeout函数
					javaShardingItemCallable.beforeTimeout();
					// 强杀
					ShardingItemFutureTask.killRunningBusinessThread(shardingItemFutureTask);
				}
			} catch (Throwable t) {
				JavaShardingItemCallable javaShardingItemCallable = shardingItemFutureTask.getCallable();
				if (javaShardingItemCallable != null) {
					LogUtils.warn(log, javaShardingItemCallable.getJobName(), "Failed to force stop timeout job", t);
				} else {
					LogUtils.warn(log, "unknown job", "Failed to force stop timeout job", t);
				}
			}
		}

	}
}
