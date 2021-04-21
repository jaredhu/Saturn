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

import com.alibaba.fastjson.JSON;
import com.vip.saturn.it.base.AbstractSaturnIT;
import com.vip.saturn.it.utils.HttpClientUtils;
import com.vip.saturn.job.console.service.SystemConfigService;
import com.vip.saturn.job.console.service.helper.SystemConfigProperties;
import com.vip.saturn.job.executor.ExecutorConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author hebelala
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ExecutorConfigIT extends AbstractSaturnIT {

	private static String CONSOLE_HOST_URL;

	@BeforeClass
	public static void setUp() throws Exception {
		startSaturnConsoleList(1);
		startExecutorList(1);
		CONSOLE_HOST_URL = saturnConsoleInstanceList.get(0).url;
	}

	@AfterClass
	public static void tearDown() throws Exception {
		stopExecutorListGracefully();
		stopSaturnConsoleList();
	}

	private void addOrUpdateConfig(String key, String value) throws IOException {
		Map<String, Object> params = new HashMap<>();
		params.put("key", key);
		params.put("value", value);
		HttpClientUtils.ResponseEntity responseEntity = HttpClientUtils
				.sendPostRequest(CONSOLE_HOST_URL + "/console/configs/executor", params);
		assertThat(responseEntity).isNotNull();
		assertThat(responseEntity.getStatusCode()).isEqualTo(200);
		assertThat(responseEntity.getEntity()).isNotNull();
		Map<String, Object> responseMap = JSON.parseObject(responseEntity.getEntity(), Map.class);
		assertThat(responseMap.get("status")).isEqualTo(0);
	}

	@Test
	public void testA() throws Exception {
		ExecutorConfig executorConfig = getExecutorConfig(0);
		assertThat(executorConfig).isNotNull();

		// 添加一个新的配置，executor无法识别该配置
		addOrUpdateConfig("whoami", "hebelala");

		SystemConfigService systemConfigService = saturnConsoleInstanceList.get(0).applicationContext
				.getBean(SystemConfigService.class);
		String dbData = systemConfigService.getValueDirectly(SystemConfigProperties.EXECUTOR_CONFIGS);
		assertThat(dbData).isNotNull();
		Map<String, Object> map = JSON.parseObject(dbData, Map.class);
		assertThat(map).containsEntry("whoami", "hebelala");

		executorConfig = getExecutorConfig(0);
		assertThat(executorConfig).isNotNull();

		// 再次添加一个新的配置，executor无法识别该配置
		addOrUpdateConfig("ruok", "ok");

		systemConfigService = saturnConsoleInstanceList.get(0).applicationContext.getBean(SystemConfigService.class);
		dbData = systemConfigService.getValueDirectly(SystemConfigProperties.EXECUTOR_CONFIGS);
		assertThat(dbData).isNotNull();
		map = JSON.parseObject(dbData, Map.class);
		assertThat(map).containsEntry("whoami", "hebelala").containsEntry("ruok", "ok");

		executorConfig = getExecutorConfig(0);
		assertThat(executorConfig).isNotNull();
	}

}
