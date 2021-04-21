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

package com.vip.saturn.it.job.SendSaturnJobReturnToChannel;

import com.vip.saturn.job.AbstractSaturnMsgJob;
import com.vip.saturn.job.SaturnJobExecutionContext;
import com.vip.saturn.job.SaturnJobReturn;
import com.vip.saturn.job.msg.MsgHolder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by xiaopeng.he on 2016/8/19.
 */
public class DemoMsgJob extends AbstractSaturnMsgJob {

	public static AtomicInteger okCount = new AtomicInteger(0);
	public static AtomicInteger failCount = new AtomicInteger(0);

	@Override
	public SaturnJobReturn handleMsgJob(String jobName, Integer shardItem, String shardParam, MsgHolder msgHolder,
			SaturnJobExecutionContext shardingContext) throws InterruptedException {
		switch (shardItem) {
		case 0:
			okCount.incrementAndGet();
			return new SaturnJobReturn("find you ok");
		case 1:
			failCount.incrementAndGet();
			return new SaturnJobReturn(5001, "find you failed", 500);
		case 2:
			int a = 1 / 0;
		case 3:
			Thread.sleep(5000);
		case 4:
			return null;
		default:
			return new SaturnJobReturn("DemoMsgJob the item is not handled");
		}
	}

}
