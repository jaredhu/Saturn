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

package com.vip.saturn.job.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * 通用的用于Spring的SaturnApplication，默认提供启动Spring的方式，即使用ClassPathXmlApplicationContext来加载applicationContext.xml文件来启动Spring。
 *
 * <p>你也可以通过重写{@link #run()}或{@link #getConfigLocations()}方法，来自定义启动Spring。
 *
 * @author hebelala
 */
public class GenericSpringSaturnApplication extends AbstractSpringSaturnApplication {

	private static final String[] CONFIG_LOCATIONS_DEFAULT = {"applicationContext.xml"};

	@Override
	public void init() {
		if (applicationContext != null) {
			destroy();
		}
		applicationContext = run();
	}

	@Override
	public void destroy() {
		if (applicationContext != null) {
			if (applicationContext instanceof ConfigurableApplicationContext) {
				((ConfigurableApplicationContext) applicationContext).close();
			}
			applicationContext = null;
		}
	}

	/**
	 * 启动Spring容器，默认使用ClassPathXmlApplicationContext来启动，默认加载applicationContext.xml文件。
	 *
	 * <p>可以通过重写{@link #getConfigLocations()}方法来加载自定义的xml文件。
	 */
	protected ApplicationContext run() {
		return new ClassPathXmlApplicationContext(getConfigLocations());
	}

	/**
	 * 使用默认的ClassPathXmlApplicationContext启动Spring容器时，加载的xml文件。
	 */
	protected String[] getConfigLocations() {
		return CONFIG_LOCATIONS_DEFAULT;
	}

}
