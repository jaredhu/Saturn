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
package com.vip.saturn.job.utils;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;

public class JsonUtils {

	private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

	private static final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").disableHtmlEscaping()
			.create();
	private static final JsonParser jsonParser = new JsonParser();
	private static final String JSON_NULL_STR;

	static {
		JSON_NULL_STR = getJsonNullStr();
	}

	private static String getJsonNullStr() {
		try {
			return gson.toJson(JsonNull.INSTANCE);
		} catch (JsonParseException e) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON, "json serialize error", e);
			return "null";
		}
	}

	public static Gson getGson() {
		return gson;
	}

	public static JsonParser getJsonParser() {
		return jsonParser;
	}

	public static String toJson(Object src) {
		try {
			return gson.toJson(src);
		} catch (JsonParseException e) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON, "json serialize error", e);
			return JSON_NULL_STR;
		}
	}

	public static String toJson(Object src, Type typeOfSrc) {
		try {
			return gson.toJson(src, typeOfSrc);
		} catch (JsonParseException e) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON, "json serialize error", e);
			return JSON_NULL_STR;
		}
	}

	public static <T> T fromJson(String json, Type typeOfT) {
		try {
			return gson.fromJson(json, typeOfT);
		} catch (JsonParseException e) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON, "json deserialize error", e);
			return null;
		}
	}

	public static <T> T fromJson(String json, Class<T> classOfT) {
		try {
			return gson.fromJson(json, classOfT);
		} catch (JsonParseException e) {
			LogUtils.warn(log, LogEvents.ExecutorEvent.COMMON, "json deserialize error", e);
			return null;
		}
	}

}
