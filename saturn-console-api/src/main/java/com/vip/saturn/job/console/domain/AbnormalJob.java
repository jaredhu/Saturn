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

package com.vip.saturn.job.console.domain;

/**
 * @author chembo.huang
 */
public class AbnormalJob extends AbstractAlarmJob {

	private String timeZone;

	private long nextFireTime;

	private String nextFireTimeWithTimeZoneFormat;

	private String cause;

	private long nextFireTimeAfterEnabledMtimeOrLastCompleteTime;

	private boolean hasRerun;

	public AbnormalJob() {
	}

	public AbnormalJob(String jobName, String domainName, String nns, String degree) {
		super(jobName, domainName, nns, degree);
	}

	public String getTimeZone() {
		return timeZone;
	}

	public void setTimeZone(String timeZone) {
		this.timeZone = timeZone;
	}

	public long getNextFireTime() {
		return nextFireTime;
	}

	public void setNextFireTime(long nextFireTime) {
		this.nextFireTime = nextFireTime;
	}

	public String getNextFireTimeWithTimeZoneFormat() {
		return nextFireTimeWithTimeZoneFormat;
	}

	public void setNextFireTimeWithTimeZoneFormat(String nextFireTimeWithTimeZoneFormat) {
		this.nextFireTimeWithTimeZoneFormat = nextFireTimeWithTimeZoneFormat;
	}

	public String getCause() {
		return cause;
	}

	public void setCause(String cause) {
		this.cause = cause;
	}

	public long getNextFireTimeAfterEnabledMtimeOrLastCompleteTime() {
		return nextFireTimeAfterEnabledMtimeOrLastCompleteTime;
	}

	public void setNextFireTimeAfterEnabledMtimeOrLastCompleteTime(
			long nextFireTimeAfterEnabledMtimeOrLastCompleteTime) {
		this.nextFireTimeAfterEnabledMtimeOrLastCompleteTime = nextFireTimeAfterEnabledMtimeOrLastCompleteTime;
	}

	public boolean isHasRerun() {
		return hasRerun;
	}

	public void setHasRerun(boolean hasRerun) {
		this.hasRerun = hasRerun;
	}

	@Override
	public int hashCode() {
		int result = jobName.hashCode();
		result = 31 * result + domainName.hashCode();
		result = 31 * result + cause.hashCode();
		result = 31 * result + (int) (nextFireTime ^ (nextFireTime >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		AbnormalJob other = (AbnormalJob) obj;
		return this.getJobName().equals(other.getJobName()) && this.getDomainName().equals(other.getDomainName())
				&& this.getCause().equals(other.getCause()) && this.getNextFireTime() == other.getNextFireTime();
	}

	@Override
	public String toString() {
		return "AbnormalJob{" + "timeZone='" + timeZone + '\'' + ", nextFireTimeWithTimeZoneFormat='"
				+ nextFireTimeWithTimeZoneFormat + '\'' + ", cause='" + cause + '\'' + ", jobName='" + jobName + '\''
				+ ", domainName='" + domainName + '\'' + '}';
	}

	public enum Cause {
		NO_SHARDS, NOT_RUN, EXECUTORS_NOT_READY
	}

}
