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

package com.vip.saturn.job.utils;

import com.google.gson.reflect.TypeToken;
import com.vip.saturn.job.exception.SaturnJobException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.util.Map;

/**
 * Util for handling alarm.
 * <p>
 * Created by jeff.zhu on 17/05/2017.
 */
public class AlarmUtils {

	private static final Logger log = LoggerFactory.getLogger(AlarmUtils.class);

	/**
	 * Send alarm request to Alarm API in Console.
	 */
	public static void raiseAlarm(Map<String, Object> alarmInfo, String namespace) throws SaturnJobException {
		int size = SystemEnvProperties.VIP_SATURN_CONSOLE_URI_LIST.size();
		for (int i = 0; i < size; i++) {

			String consoleUri = SystemEnvProperties.VIP_SATURN_CONSOLE_URI_LIST.get(i);
			String targetUrl = consoleUri + "/rest/v1/" + namespace + "/alarms/raise";

			LogUtils.info(log, LogEvents.ExecutorEvent.COMMON,
					"raise alarm of domain {} to url {}: {}, retry count: {}", namespace, targetUrl,
					alarmInfo.toString(), i);
			CloseableHttpClient httpClient = null;
			try {
				checkParameters(alarmInfo);
				// prepare
				httpClient = HttpClientBuilder.create().build();
				HttpPost request = new HttpPost(targetUrl);
				final RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(5000)
						.setSocketTimeout(10000).build();
				request.setConfig(requestConfig);
				StringEntity params = new StringEntity(
						JsonUtils.getGson().toJson(alarmInfo, new TypeToken<Map<String, Object>>() {
						}.getType()));
				request.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
				request.setEntity(params);

				// send request
				CloseableHttpResponse httpResponse = httpClient.execute(request);
				// handle response
				HttpUtils.handleResponse(httpResponse);
				return;
			} catch (SaturnJobException se) {
				LogUtils.error(log, LogEvents.ExecutorEvent.COMMON, "SaturnJobException throws: {}", se.getMessage(),
						se);
				throw se;
			} catch (ConnectException e) {
				LogUtils.error(log, LogEvents.ExecutorEvent.COMMON, "Fail to connect to url:{}, throws: {}", targetUrl,
						e.getMessage(), e);
				if (i == size - 1) {
					throw new SaturnJobException(SaturnJobException.SYSTEM_ERROR, "no available console server", e);
				}
			} catch (Exception e) {
				LogUtils.error(log, LogEvents.ExecutorEvent.COMMON, "Other exception throws: {}", e.getMessage(), e);
				throw new SaturnJobException(SaturnJobException.SYSTEM_ERROR, e.getMessage(), e);
			} finally {
				HttpUtils.closeHttpClientQuietly(httpClient);
			}
		}
	}

	private static void checkParameters(Map<String, Object> alarmInfo) throws SaturnJobException {
		if (alarmInfo == null) {
			throw new SaturnJobException(SaturnJobException.ILLEGAL_ARGUMENT, "alarmInfo cannot be null.");
		}

		String level = (String) alarmInfo.get("level");
		if (StringUtils.isBlank(level)) {
			throw new SaturnJobException(SaturnJobException.ILLEGAL_ARGUMENT, "level cannot be blank.");
		}

		String name = (String) alarmInfo.get("name");
		if (StringUtils.isBlank(name)) {
			throw new SaturnJobException(SaturnJobException.ILLEGAL_ARGUMENT, "name cannot be blank.");
		}

		String title = (String) alarmInfo.get("title");
		if (StringUtils.isBlank(title)) {
			throw new SaturnJobException(SaturnJobException.ILLEGAL_ARGUMENT, "title cannot be blank.");
		}

	}

}
