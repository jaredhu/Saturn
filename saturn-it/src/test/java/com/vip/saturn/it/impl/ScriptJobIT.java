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

package com.vip.saturn.it.impl;

import com.vip.saturn.it.base.AbstractSaturnIT;
import com.vip.saturn.it.base.FinishCheck;
import com.vip.saturn.job.console.domain.JobConfig;
import com.vip.saturn.job.console.domain.JobType;
import com.vip.saturn.job.internal.execution.ExecutionNode;
import com.vip.saturn.job.internal.storage.JobNodePath;
import com.vip.saturn.job.utils.ScriptPidUtils;
import org.apache.commons.exec.OS;
import org.junit.*;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.Random;

import static org.assertj.core.api.Assertions.fail;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ScriptJobIT extends AbstractSaturnIT {
	public static String NORMAL_SH_PATH;
	public static String LONG_TIME_SH_PATH;

	@BeforeClass
	public static void setUp() throws Exception {
		startSaturnConsoleList(1);

		NORMAL_SH_PATH = new File("src/test/resources/script/normal/normal_0.sh").getAbsolutePath();
		LONG_TIME_SH_PATH = new File("src/test/resources/script/normal/longtime.sh").getAbsolutePath();
	}

	@AfterClass
	public static void tearDown() throws Exception {
		stopExecutorListGracefully();
		stopSaturnConsoleList();
	}

	@After
	public void after() throws Exception {
		stopExecutorListGracefully();
	}

	@Test
	public void test_A_Normalsh() throws Exception {
		if (!OS.isFamilyUnix()) {
			return;
		}
		startOneNewExecutorList();

		final String jobName = "test_A_Normalsh";
		JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("*/4 * * * * ?");
		jobConfig.setJobType(JobType.SHELL_JOB.toString());
		jobConfig.setShardingTotalCount(1);
		jobConfig.setProcessCountIntervalSeconds(1);
		jobConfig.setShardingItemParameters("0=sh " + NORMAL_SH_PATH);
		addJob(jobConfig);
		Thread.sleep(1000);
		enableJob(jobName);
		Thread.sleep(1000);

		try {
			waitForFinish(new FinishCheck() {

				@Override
				public boolean isOk() {
					String count = zkGetJobNode(jobName,
							"servers/" + saturnExecutorList.get(0).getExecutorName() + "/processSuccessCount");
					log.info("success count: {}", count);
					int cc = Integer.parseInt(count);
					if (cc > 0) {
						return true;
					}
					return false;
				}
			}, 15);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		disableJob(jobName);
		Thread.sleep(1000);
		removeJob(jobName);
	}

	/**
	 * 作业启用状态，关闭Executor，将强停作业
	 */
	@Test
	public void test_B_ForceStop() throws Exception {
		// bacause ScriptPidUtils.isPidRunning don't support mac
		if (!OS.isFamilyUnix() || OS.isFamilyMac()) {
			return;
		}

		final int shardCount = 3;
		final String jobName = "test_B_ForceStop_" + new Random().nextInt(100); // 避免多个IT同时跑该作业

		JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("9 9 9 9 9 ? 2099");
		jobConfig.setJobType(JobType.SHELL_JOB.toString());
		jobConfig.setShardingTotalCount(shardCount);
		jobConfig.setShardingItemParameters(
				"0=sh " + LONG_TIME_SH_PATH + ",1=sh " + LONG_TIME_SH_PATH + ",2=sh " + LONG_TIME_SH_PATH);

		addJob(jobConfig);
		Thread.sleep(1000);

		startOneNewExecutorList(); // 将会删除该作业的一些pid垃圾数据
		Thread.sleep(1000);
		final String executorName = saturnExecutorList.get(0).getExecutorName();

		enableJob(jobName);
		Thread.sleep(1000);
		runAtOnce(jobName);
		Thread.sleep(2000);

		// 不优雅退出，直接关闭
		stopExecutor(0);

		try {
			waitForFinish(new FinishCheck() {

				@Override
				public boolean isOk() {

					for (int j = 0; j < shardCount; j++) {
						long pid = ScriptPidUtils.getFirstPidFromFile(executorName, jobName, "" + j);
						if (pid > 0 && ScriptPidUtils.isPidRunning(pid)) {
							return false;
						}
					}

					return true;
				}

			}, 10);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		disableJob(jobName);
		Thread.sleep(1000);
		removeJob(jobName);
	}

	/**
	 * 作业禁用状态，关闭Executor，分片进程不强杀。 下次启动Executor，将其正在运行分片，重新持久化分片状态（running节点），并监听其状态（运行完，删除running节点，持久化completed节点）
	 */
	@Test
	public void test_C_ReuseItem() throws Exception {
		// because ScriptPidUtils.isPidRunning don't supoort mac
		if (!OS.isFamilyUnix() || OS.isFamilyMac()) {
			return;
		}

		final int shardCount = 3;
		final String jobName = "test_C_ReuseItem" + new Random().nextInt(100); // 避免多个IT同时跑该作业

		JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("9 9 9 9 9 ? 2099");
		jobConfig.setJobType(JobType.SHELL_JOB.toString());
		jobConfig.setShardingTotalCount(shardCount);
		jobConfig.setShardingItemParameters(
				"0=sh " + LONG_TIME_SH_PATH + ",1=sh " + LONG_TIME_SH_PATH + ",2=sh " + LONG_TIME_SH_PATH);

		addJob(jobConfig);
		Thread.sleep(1000);

		startOneNewExecutorList(); // 将会删除该作业的一些pid垃圾数据
		Thread.sleep(1000);
		final String executorName = saturnExecutorList.get(0).getExecutorName();

		enableJob(jobName);
		Thread.sleep(1000);
		runAtOnce(jobName);
		Thread.sleep(1000);
		disableJob(jobName);
		Thread.sleep(1000);

		// 不优雅退出，直接关闭
		stopExecutor(0);

		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {
					return !isOnline(executorName);
				}

			}, 10);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		for (int j = 0; j < shardCount; j++) {
			long pid = ScriptPidUtils.getFirstPidFromFile(executorName, jobName, "" + j);
			if (pid < 0 || !ScriptPidUtils.isPidRunning(pid)) {
				fail("item " + j + ", pid " + pid + " should running");
			}
		}

		startOneNewExecutorList();
		Thread.sleep(2000);

		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {

					for (int j = 0; j < shardCount; j++) {
						if (!regCenter
								.isExisted(JobNodePath.getNodeFullPath(jobName, ExecutionNode.getRunningNode(j)))) {
							return false;
						}
					}
					return true;
				}

			}, 10);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		forceStopJob(jobName);
		Thread.sleep(1000);

		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {

					for (int j = 0; j < shardCount; j++) {
						if (!regCenter
								.isExisted(JobNodePath.getNodeFullPath(jobName, ExecutionNode.getCompletedNode(j)))) {
							return false;
						}
					}
					return true;
				}

			}, 10);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		removeJob(jobName);
	}

}
