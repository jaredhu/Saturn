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
import com.vip.saturn.it.job.LongtimeJavaJob;
import com.vip.saturn.job.console.domain.JobConfig;
import com.vip.saturn.job.console.domain.JobType;
import com.vip.saturn.job.internal.execution.ExecutionNode;
import com.vip.saturn.job.internal.sharding.ShardingNode;
import com.vip.saturn.job.internal.storage.JobNodePath;
import com.vip.saturn.job.utils.ItemUtils;
import org.junit.*;
import org.junit.runners.MethodSorters;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FailoverIT extends AbstractSaturnIT {

	@BeforeClass
	public static void setUp() throws Exception {
		startSaturnConsoleList(1);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		stopExecutorListGracefully();
		stopSaturnConsoleList();
	}

	@Before
	public void before() {
		LongtimeJavaJob.statusMap.clear();
	}

	@After
	public void after() {
		LongtimeJavaJob.statusMap.clear();
	}

	/**
	 * 场景1：如果有空闲的Executor，failover就会立即执行，不需要等到主节点sharding完成 Executor个数 > 分片个数的情况
	 */
	@Test
	public void test_A_JavaJob() throws Exception {
		startExecutorList(3);
		final int shardCount = 2;
		final String jobName = "failoverITJobJava1";
		failover(shardCount, jobName);
		stopExecutorListGracefully();
	}

	/**
	 * 场景2：普通的failover场景 Executor个数 = 分片个数的情况
	 */
	@Test
	public void test_B_JavaJob() throws Exception {
		startExecutorList(2);
		final int shardCount = 2;
		final String jobName = "failoverITJobJava2";
		failover(shardCount, jobName);
		stopExecutorListGracefully();
	}

	/**
	 * 场景3：在failover执行之前禁用的作业重新启用后不应该继续上次的failover流程
	 */
	@Test
	public void test_C_JavaJob() throws Exception {
		startExecutorList(2);
		final int shardCount = 2;
		final String jobName = "failoverITJobJava3";
		failoverWithDisabled(shardCount, jobName, 2);
		stopExecutorListGracefully();
	}

	/**
	 * 禁用作业，停止executor，业务正在运行，java作业仍将被强杀
	 */
	@Test
	public void test_D_disabledJavaJobStillBeAborted() throws Exception {
		startExecutorList(2);
		final int shardCount = 2;
		final String jobName = "failoverITJobJava4";
		failoverWithDisabled(shardCount, jobName, 1);
		stopExecutorListGracefully();
	}

	private void failover(final int shardCount, final String jobName) throws Exception {
		for (int i = 0; i < shardCount; i++) {
			String key = jobName + "_" + i;
			LongtimeJavaJob.JobStatus status = new LongtimeJavaJob.JobStatus();
			status.runningCount = 0;
			status.sleepSeconds = 10;
			status.finished = false;
			status.timeout = false;
			LongtimeJavaJob.statusMap.put(key, status);
		}

		// 1 新建一个执行时间为10S的作业，它只能手工触发
		JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("9 9 9 9 9 ? 2099");
		jobConfig.setJobType(JobType.JAVA_JOB.toString());
		jobConfig.setJobClass(LongtimeJavaJob.class.getCanonicalName());
		jobConfig.setShardingTotalCount(shardCount);
		jobConfig.setShardingItemParameters("0=0,1=1,2=2");
		addJob(jobConfig);
		Thread.sleep(1000);

		// 2 启动作业并立刻执行一次
		enableJob(jobName);
		Thread.sleep(2000);
		runAtOnce(jobName);

		// 3 保证全部作业分片正在运行中
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

			}, 6);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		Thread.sleep(2000);
		final List<Integer> items = ItemUtils.toItemList(regCenter.getDirectly(JobNodePath
				.getNodeFullPath(jobName, ShardingNode.getShardingNode(saturnExecutorList.get(0).getExecutorName()))));

		// 4 停止第一个executor，在该executor上运行的分片会失败转移
		stopExecutor(0);
		System.out.println("items:" + items);
		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {

					for (Integer item : items) {
						if (!isFailoverAssigned(jobName, item)) {
							return false;
						}
					}
					return true;
				}

			}, 20);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		Thread.sleep(1000);

		// 5 检查停止的executor 上面的分片是否已经被KILL
		for (Integer item : items) {
			String key = jobName + "_" + item;
			LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
			if (!status.finished || status.killed == 0) {
				fail("should finish and killed");
			}
			status.runningCount = 0;
		}

		// 6 保证全部分片都会执行一次（被停止的executor上的分片会失败转移从而也会执行一次）
		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {

					for (int j = 0; j < shardCount; j++) {
						String key = jobName + "_" + j;
						LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
						if (status.runningCount <= 0) {
							return false;
						}
					}
					return true;
				}

			}, 60);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		disableJob(jobName);
		Thread.sleep(1000);
		removeJob(jobName);
		Thread.sleep(2000);
		LongtimeJavaJob.statusMap.clear();
	}

	/**
	 * 在failover执行之前禁用的作业重新启用后不应该继续上次的failover流程
	 */
	private void failoverWithDisabled(final int shardCount, final String jobName, final int disableTime)
			throws Exception {
		for (int i = 0; i < shardCount; i++) {
			String key = jobName + "_" + i;
			LongtimeJavaJob.JobStatus status = new LongtimeJavaJob.JobStatus();
			status.runningCount = 0;
			status.sleepSeconds = 20;
			status.finished = false;
			status.timeout = false;
			LongtimeJavaJob.statusMap.put(key, status);
		}

		// 1 新建一个执行时间为10S的作业，它只能手工触发
		final JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("9 9 9 9 9 ? 2099");
		jobConfig.setJobType(JobType.JAVA_JOB.toString());
		jobConfig.setJobClass(LongtimeJavaJob.class.getCanonicalName());
		jobConfig.setShardingTotalCount(shardCount);
		jobConfig.setShardingItemParameters("0=0,1=1,2=2");
		addJob(jobConfig);
		Thread.sleep(1000);

		// 2 启动作业并立刻执行一次
		enableJob(jobName);
		Thread.sleep(2000);
		runAtOnce(jobName);

		// 3 保证全部作业分片正在运行中
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

			}, 6);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		Thread.sleep(2000);
		final String firstExecutorName = saturnExecutorList.get(0).getExecutorName();
		final List<Integer> items = ItemUtils.toItemList(regCenter
				.getDirectly(JobNodePath.getNodeFullPath(jobName, ShardingNode.getShardingNode(firstExecutorName))));

		final String secondExecutorName = saturnExecutorList.get(1).getExecutorName();
		final List<Integer> items2 = ItemUtils.toItemList(regCenter
				.getDirectly(JobNodePath.getNodeFullPath(jobName, ShardingNode.getShardingNode(secondExecutorName))));

		if (disableTime == 1) {
			disableJob(jobName);
		}

		// 4 停止第一个executor，在该executor上运行的分片会失败转移
		stopExecutor(0);
		System.out.println("items:" + items);

		// 5 直到第一个Executor完全下线
		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {
					if (isOnline(firstExecutorName)) {// 判断该Executor是否在线
						return false;
					}
					return true;
				}

			}, 20);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		// 6 检查停止的executor 上面的分片是否已经被KILL
		for (Integer item : items) {
			String key = jobName + "_" + item;
			LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
			if (!status.finished || status.killed == 0) {
				fail("should finish and killed");
			}
			status.runningCount = 0;
		}

		// 7 检查运行executor2上的分片都正在运行，而且runningCount为0
		for (Integer item : items2) {
			String key = jobName + "_" + item;
			LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
			if (status.finished || status.killed > 0 || status.timeout) {
				fail("should running");
			}
			if (status.runningCount != 0) {
				fail("runningCount should be 0");
			}
		}

		// 8 禁用作业
		if (disableTime == 2) {
			disableJob(jobName);
		}

		// 9 等待executor2分片运行完
		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {
					for (Integer item : items2) {
						String key = jobName + "_" + item;
						LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
						if (!status.finished) {
							return false;
						}
					}
					return true;
				}

			}, 20);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

		// 10 检测无failover信息
		assertThat(noFailoverItems(jobName));
		for (Integer item : items) {
			assertThat(isFailoverAssigned(jobName, item)).isEqualTo(false);
		}

		// 11 检测只executor2的分片只运行了一次
		Thread.sleep(2000);
		for (Integer item : items2) {
			String key = jobName + "_" + item;
			LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
			if (status.runningCount != 1) {
				fail("runningCount should be 1");
			}
		}

		removeJob(jobName);
		Thread.sleep(2000);
		LongtimeJavaJob.statusMap.clear();
	}

}
