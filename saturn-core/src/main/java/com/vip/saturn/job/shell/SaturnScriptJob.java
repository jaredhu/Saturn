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

package com.vip.saturn.job.shell;

import com.vip.saturn.job.SaturnJobReturn;
import com.vip.saturn.job.SaturnSystemErrorGroup;
import com.vip.saturn.job.SaturnSystemReturnCode;
import com.vip.saturn.job.basic.AbstractSaturnJob;
import com.vip.saturn.job.basic.SaturnExecutionContext;
import com.vip.saturn.job.basic.ShardingItemCallable;
import com.vip.saturn.job.utils.LogUtils;
import com.vip.saturn.job.utils.ScriptPidUtils;
import com.vip.saturn.job.utils.SystemEnvProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * 处理通用Script的调度(也支持PHP)
 *
 * @author linzhaoming
 */
public class SaturnScriptJob extends AbstractSaturnJob {

	private static Logger log = LoggerFactory.getLogger(SaturnScriptJob.class);

	private Object watchDogLock = new Object();

	protected List<SaturnExecuteWatchdog> watchDogList = new ArrayList<SaturnExecuteWatchdog>();
	protected List<ShardingItemCallable> shardingItemCallableList = new ArrayList<>();

	private Random random = new Random();

	@Override
	public Map<Integer, SaturnJobReturn> handleJob(final SaturnExecutionContext shardingContext) {
		synchronized (watchDogList) {
			watchDogList.clear();
		}
		shardingItemCallableList.clear();

		final Map<Integer, SaturnJobReturn> retMap = new ConcurrentHashMap<Integer, SaturnJobReturn>();

		Map<Integer, String> shardingItemParameters = shardingContext.getShardingItemParameters();

		final String jobName = shardingContext.getJobName();

		ExecutorService executorService = getExecutorService();

		// 处理自定义参数
		String jobParameter = shardingContext.getJobParameter();

		final CountDownLatch latch = new CountDownLatch(shardingItemParameters.size());

		for (final Entry<Integer, String> shardingItem : shardingItemParameters.entrySet()) {
			final Integer key = shardingItem.getKey();
			String jobValue = shardingItem.getValue();

			final String execParameter = getRealItemValue(jobParameter, jobValue); // 作业分片的对应值

			LogUtils.debug(log, jobName, "jobname={}, key= {}, jobParameter={}", jobName, key, execParameter);
			executorService.submit(new Runnable() {
				@Override
				public void run() {
					SaturnJobReturn jobReturn = null;
					try {
						jobReturn = innerHandleWithListener(jobName, key, execParameter, shardingContext);
					} catch (Throwable e) {
						LogUtils.error(log, jobName, e.getMessage(), e);
						jobReturn = new SaturnJobReturn(SaturnSystemReturnCode.USER_FAIL, "Error: " + e.getMessage(),
								SaturnSystemErrorGroup.FAIL);
					} finally {
						retMap.put(key, jobReturn);
						latch.countDown();
					}
				}
			});
		}

		try {
			latch.await();
		} catch (final InterruptedException ex) {
			LogUtils.error(log, jobName, "SaturnScriptJob: Job {} is interrupted", jobName);
			Thread.currentThread().interrupt();
		}

		return retMap;
	}

	public void beforeExecution(ShardingItemCallable callable) {
		callable.setStartTime(System.currentTimeMillis());
	}

	public void afterExecution(ShardingItemCallable callable) {
		callable.setEndTime(System.currentTimeMillis());
	}

	public ShardingItemCallable createShardingItemCallable(String jobName, Integer item, String execParameter,
			SaturnExecutionContext shardingContext) {
		ShardingItemCallable callable = new ShardingItemCallable(jobName, item, execParameter, getTimeoutSeconds(),
				shardingContext, this);
		return callable;
	}

	protected SaturnJobReturn innerHandleWithListener(String jobName, Integer item, String execParameter,
			SaturnExecutionContext shardingContext) {

		ShardingItemCallable callable = createShardingItemCallable(jobName, item, execParameter, shardingContext);
		shardingItemCallableList.add(callable);

		beforeExecution(callable);

		SaturnJobReturn saturnJobReturn = null;
		try {
			saturnJobReturn = innerHandle(callable);
		} catch (Throwable t) {
			LogUtils.error(log, jobName, t.getMessage(), t);
			saturnJobReturn = new SaturnJobReturn(SaturnSystemReturnCode.USER_FAIL, t.getMessage(),
					SaturnSystemErrorGroup.FAIL);
		}

		callable.setSaturnJobReturn(saturnJobReturn);
		afterExecution(callable);

		LogUtils.debug(log, jobName, "job:{} item:{} finish execution, which takes {}ms", jobName, item,
				callable.getExecutionTime());

		return saturnJobReturn;
	}

	protected SaturnJobReturn innerHandle(ShardingItemCallable callable) {
		SaturnJobReturn saturnJobReturn = null;
		try {
			String saturnOutputPath = String
					.format(ScriptPidUtils.JOBITEMOUTPUTPATH, callable.getShardingContext().getExecutorName(), jobName,
							callable.getItem(), random.nextInt(10000), System.currentTimeMillis());
			callable.getEnvMap().put(SystemEnvProperties.NAME_VIP_SATURN_OUTPUT_PATH, saturnOutputPath);

			ScriptJobRunner scriptJobRunner = new ScriptJobRunner(callable.getEnvMap(), this, callable.getItem(),
					callable.getItemValue(), callable.getShardingContext());
			SaturnExecuteWatchdog watchDog = scriptJobRunner.getWatchdog();
			synchronized (watchDogList) {
				watchDogList.add(watchDog);
			}
			saturnJobReturn = scriptJobRunner.runJob();
			synchronized (watchDogLock) {
				watchDogList.remove(watchDog);
			}
			callable.setBusinessReturned(scriptJobRunner.isBusinessReturned());
		} catch (Throwable t) {
			LogUtils.error(log, jobName, t.getMessage(), t);
			saturnJobReturn = new SaturnJobReturn(SaturnSystemReturnCode.USER_FAIL, t.getMessage(),
					SaturnSystemErrorGroup.FAIL);
		}
		return saturnJobReturn;
	}

	@Override
	public void forceStop() {
		super.forceStop();
		LogUtils.info(log, jobName, "shell executor invoked forceStop, watchDogList = {}", watchDogList);
		if (watchDogList == null || watchDogList.isEmpty()) {
			ScriptPidUtils.forceStopRunningShellJob(executorName, jobName);
		} else {
			List<SaturnExecuteWatchdog> tmp = new ArrayList<SaturnExecuteWatchdog>();
			synchronized (watchDogLock) {
				tmp.addAll(watchDogList);
			}

			for (SaturnExecuteWatchdog watchDog : tmp) {
				LogUtils.info(log, jobName, "Job {}-{} is stopped, force the script {} to exit.", watchDog.getJobName(),
						watchDog.getJobItem(), watchDog.getExecParam());
				// kill process and stop watchdog, mark forceStop
				// it will use kill, but not kill -9.
				watchDog.destroyProcess();

				// use kill -9
				int jobItem = watchDog.getJobItem();
				long pid = ScriptPidUtils.getFirstPidFromFile(serverService.getExecutorName(), watchDog.getJobName(),
						"" + Integer.toString(jobItem));
				if (pid > 0 && ScriptPidUtils.isPidRunning(pid)) {
					ScriptPidUtils.killAllChildrenByPid(pid, true);
				}

				// remove pid files
				ScriptPidUtils.removeAllPidFile(serverService.getExecutorName(), watchDog.getJobName(), jobItem);

				onForceStop(jobItem);
			}
		}

	}

	@Override
	public void abort() {
		super.abort();
		forceStop();
	}

	@Override
	public void onForceStop(int item) {
	}

	@Override
	public void onTimeout(int item) {
	}

	@Override
	public void onNeedRaiseAlarm(int item, String alarmMessage) {
	}

}
