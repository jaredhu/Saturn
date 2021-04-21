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
public class FailoverWithPreferListIT extends AbstractSaturnIT {

	@BeforeClass
	public static void setUp() throws Exception {
		startSaturnConsoleList(1);
		startExecutorList(2);
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

	@Test
	public void test_A_JavaJob() throws Exception {
		final int shardCount = 2;
		final String jobName = "failoverWithPreferITJobJava";
		for (int i = 0; i < shardCount; i++) {
			String key = jobName + "_" + i;
			LongtimeJavaJob.JobStatus status = new LongtimeJavaJob.JobStatus();
			status.runningCount = 0;
			status.sleepSeconds = 10;
			status.finished = false;
			status.timeout = false;
			LongtimeJavaJob.statusMap.put(key, status);
		}

		// 1 新建一个执行时间为10S的作业，它只能手工触发，它设置了preferList为executor0，并且只能使用优先结点
		final JobConfig jobConfig = new JobConfig();
		jobConfig.setJobName(jobName);
		jobConfig.setCron("9 9 9 9 9 ? 2099");
		jobConfig.setJobType(JobType.JAVA_JOB.toString());
		jobConfig.setJobClass(LongtimeJavaJob.class.getCanonicalName());
		jobConfig.setShardingTotalCount(shardCount);
		jobConfig.setPreferList(saturnExecutorList.get(0).getExecutorName());
		jobConfig.setUseDispreferList(false);
		jobConfig.setShardingItemParameters("0=0,1=1,2=2");
		addJob(jobConfig);
		Thread.sleep(1000);

		// 2 启动作业并立刻执行一次
		enableJob(jobConfig.getJobName());
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

			}, 30);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		Thread.sleep(2000);
		final List<Integer> items = ItemUtils.toItemList(regCenter.getDirectly(JobNodePath
				.getNodeFullPath(jobName, ShardingNode.getShardingNode(saturnExecutorList.get(0).getExecutorName()))));

		// 4 停止第一个executor，在该executor上运行的分片不会失败转移(因为优先结点已经全部死掉了)
		stopExecutor(0);

		System.out.println("items:" + items);
		try {
			waitForFinish(new FinishCheck() {
				@Override
				public boolean isOk() {

					for (int j = 0; j < shardCount; j++) {
						if (regCenter
								.isExisted(JobNodePath.getNodeFullPath(jobName, ExecutionNode.getRunningNode(j)))) {
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

		// 5 没有失败转移，executor0上面运行的分片不会被执行
		for (Integer item : items) {
			String key = jobName + "_" + item;
			LongtimeJavaJob.JobStatus status = LongtimeJavaJob.statusMap.get(key);
			assertThat(status.runningCount).isEqualTo(0);
		}

		disableJob(jobConfig.getJobName());
		Thread.sleep(1000);
		removeJob(jobConfig.getJobName());
		Thread.sleep(2000);
		LongtimeJavaJob.statusMap.clear();
	}

}
