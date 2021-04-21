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

package com.vip.saturn.job.console.service.impl.statistics;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.vip.saturn.job.console.domain.*;
import com.vip.saturn.job.console.exception.SaturnJobConsoleException;
import com.vip.saturn.job.console.mybatis.entity.DashboardHistory;
import com.vip.saturn.job.console.mybatis.entity.SaturnStatistics;
import com.vip.saturn.job.console.mybatis.service.SaturnStatisticsService;
import com.vip.saturn.job.console.repository.zookeeper.CuratorRepository;
import com.vip.saturn.job.console.service.*;
import com.vip.saturn.job.console.service.helper.DashboardConstants;
import com.vip.saturn.job.console.service.helper.ZkClusterMappingUtils;
import com.vip.saturn.job.console.service.impl.DashboardServiceImpl;
import com.vip.saturn.job.console.service.impl.statistics.analyzer.*;
import com.vip.saturn.job.console.utils.ConsoleThreadFactory;
import com.vip.saturn.job.console.utils.JobNodePath;
import com.vip.saturn.job.console.utils.StatisticsTableKeyConstant;
import com.vip.saturn.job.integrate.service.ReportAlarmService;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.*;

/**
 * @author timmy.hu
 */
public class StatisticsRefreshServiceImpl implements StatisticsRefreshService {

	private static final Logger log = LoggerFactory.getLogger(StatisticsRefreshServiceImpl.class);

	private static final int CONNECT_TIMEOUT_MS = 10000;

	private static final int SO_TIMEOUT_MS = 180_000;

	private static final int STAT_THREAD_NUM = 20;

	private Timer refreshStatisticsTimer;

	private Timer cleanAbnormalShardingCacheTimer;

	private Map<String/** domainName_jobName_shardingItemStr **/
			, AbnormalShardingState /** abnormal sharding state */> abnormalShardingStateCache = new ConcurrentHashMap<>();

	@Resource
	private SaturnStatisticsService saturnStatisticsService;

	@Resource
	private StatisticsPersistence statisticsPersistence;

	@Resource
	private SystemConfigService systemConfigService;

	@Resource
	private RegistryCenterService registryCenterService;

	@Resource
	private JobService jobService;

	@Resource
	private ReportAlarmService reportAlarmService;

	private ExecutorService statExecutorService;

	@Resource
	private DashboardService dashboardService;

	@PostConstruct
	public void init() {
		initStatExecutorService();
		startRefreshStatisticsTimer();
		startCleanAbnormalShardingCacheTimer();
	}

	@PreDestroy
	public void destroy() {
		if (statExecutorService != null) {
			statExecutorService.shutdownNow();
		}
		if (refreshStatisticsTimer != null) {
			refreshStatisticsTimer.cancel();
		}
		if (cleanAbnormalShardingCacheTimer != null) {
			cleanAbnormalShardingCacheTimer.cancel();
		}
	}

	private void initStatExecutorService() {
		if (statExecutorService != null) {
			statExecutorService.shutdownNow();
		}
		ThreadPoolExecutor tp = new ThreadPoolExecutor(STAT_THREAD_NUM, STAT_THREAD_NUM,
				DashboardConstants.REFRESH_INTERVAL_IN_MINUTE + 1, TimeUnit.MINUTES,
				new LinkedBlockingQueue<Runnable>(), new ConsoleThreadFactory("dashboard-statistics-thread", true));
		tp.allowCoreThreadTimeOut(true);
		statExecutorService = tp;
	}

	private void startRefreshStatisticsTimer() {
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				try {
					log.info("start refresh statistics on timer");
					Date start = new Date();
					Collection<ZkCluster> zkClusterList = registryCenterService.getZkClusterList();
					if (zkClusterList != null) {
						for (ZkCluster zkCluster : zkClusterList) {
							if (zkCluster.isOffline()) {
								log.info("zkcluster:{} is offline, skip statistics refresh.",
										zkCluster.getZkClusterKey());
								continue;
							}

							if (registryCenterService.isDashboardLeader(zkCluster.getZkClusterKey())) {
								refreshStatistics2DB(zkCluster);
							}
						}
					}
					log.info("end refresh statistics on timer which takes {}ms",
							new Date().getTime() - start.getTime());
				} catch (Throwable t) {
					log.error(t.getMessage(), t);
				}
			}
		};
		refreshStatisticsTimer = new Timer("refresh-statistics-to-db-timer", true);
		refreshStatisticsTimer
				.scheduleAtFixedRate(timerTask, 1000L * 15, 1000L * 60 * DashboardConstants.REFRESH_INTERVAL_IN_MINUTE);
	}

	private void startCleanAbnormalShardingCacheTimer() {
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				try {
					for (Entry<String, AbnormalShardingState> entrySet : abnormalShardingStateCache.entrySet()) {
						AbnormalShardingState shardingState = entrySet.getValue();
						if (shardingState.getAlertTime() + DashboardConstants.ALLOW_DELAY_MILLIONSECONDS * 2 < System
								.currentTimeMillis()) {
							abnormalShardingStateCache.remove(entrySet.getKey());
							log.info("Clean AbnormalShardingCache with key: {}, alertTime: {}, zkNodeCVersion: {}: ",
									entrySet.getKey(), shardingState.getAlertTime(), shardingState.getZkNodeCVersion());
						}
					}
				} catch (Throwable t) {
					log.error("Clean AbnormalShardingCache error", t);
				}
			}
		};
		cleanAbnormalShardingCacheTimer = new Timer("clean-abnormalShardingCache-timer", true);
		cleanAbnormalShardingCacheTimer
				.scheduleAtFixedRate(timerTask, 0, DashboardConstants.ALLOW_DELAY_MILLIONSECONDS);
	}

	@Override
	public void refresh(String zkClusterKey, boolean isForce) throws SaturnJobConsoleException {
		if (isForce) {
			refresh(zkClusterKey);
			return;
		}

		boolean isSameIdc = ZkClusterMappingUtils.isCurrentConsoleInTheSameIdc(systemConfigService, zkClusterKey);
		if (isSameIdc) {
			log.info("the zk and the console are in the same IDC, refreshStatistics in the current Console");
			refresh(zkClusterKey);
		} else {
			log.info("the zk and the console are in different IDC, forward the refresh request to remote console");
			try {
				forwardDashboardRefreshToRemote(zkClusterKey);
			} catch (SaturnJobConsoleException e) {
				log.warn("remote refresh request error, so refreshStatistics in the current Console", e);
				refresh(zkClusterKey);
			}
		}
	}

	private void refresh(String zkClusterKey) throws SaturnJobConsoleException {
		ZkCluster zkCluster = registryCenterService.getZkCluster(zkClusterKey);
		if (zkCluster != null) {
			refreshStatistics2DB(zkCluster);
		} else {
			throw new SaturnJobConsoleException("zk cluster not found by zkClusterKey:" + zkClusterKey);
		}
	}

	protected void postRefreshStatistics2DB(StatisticsModel statisticsModel, ZkCluster zkCluster) {
		statisticsModel.getOutdatedNoRunningJobAnalyzer().reportAlarmOutdatedNoRunningJobs();
		calculateDashboardHistory(statisticsModel, zkCluster);
	}

	private void calculateDashboardHistory(StatisticsModel statisticsModel, ZkCluster zkCluster) {
		List<DashboardHistory> dashboardHistories = new ArrayList<>();
		Date currentDate = new Date();
		calculateRunTimes(statisticsModel, zkCluster, dashboardHistories, currentDate);
		calculateContainerCount(statisticsModel, zkCluster, dashboardHistories, currentDate);
		calculateJobCount(statisticsModel, zkCluster, dashboardHistories, currentDate);
		calculateDomainCount(statisticsModel, zkCluster, dashboardHistories, currentDate);
		dashboardService.batchSaveDashboardHistory(dashboardHistories);
	}

	private void calculateDomainCount(StatisticsModel statisticsModel, ZkCluster zkCluster,
			List<DashboardHistory> dashboardHistories, Date currentDate) {
		Map<String, Integer> domainContent = new HashMap<>(1);
		int domainCount = registryCenterService.domainCount(zkCluster.getZkClusterKey());
		domainContent.put("domainCount", domainCount);
		DashboardHistory domainHistory = new DashboardHistory(zkCluster.getZkClusterKey(),
				DashboardServiceImpl.DashboardType.DOMAIN.name(),
				DashboardServiceImpl.DashboardTopic.DOMAIN_COUNT.name(), JSON.toJSONString(domainContent), currentDate);
		dashboardHistories.add(domainHistory);
	}

	private void calculateJobCount(StatisticsModel statisticsModel, ZkCluster zkCluster,
			List<DashboardHistory> dashboardHistories, Date currentDate) {
		Map<String, Integer> jobContent = new HashMap<>(1);
		int jobCount = statisticsModel.getJobStatisticsAnalyzer().getJobList().size();
		jobContent.put("jobCount", jobCount);
		DashboardHistory jobHistory = new DashboardHistory(zkCluster.getZkClusterKey(),
				DashboardServiceImpl.DashboardType.JOB.name(), DashboardServiceImpl.DashboardTopic.JOB_COUNT.name(),
				JSON.toJSONString(jobContent), currentDate);
		dashboardHistories.add(jobHistory);
	}

	private void calculateContainerCount(StatisticsModel statisticsModel, ZkCluster zkCluster,
			List<DashboardHistory> dashboardHistories, Date currentDate) {
		Map<String, Integer> executorContent = new HashMap<>(2);
		int inDocker = statisticsModel.getExecutorInfoAnalyzer().getExeInDocker();
		int notInDocker = statisticsModel.getExecutorInfoAnalyzer().getExeNotInDocker();
		executorContent.put("dockerCount", inDocker);
		executorContent.put("otherCount", notInDocker);
		DashboardHistory executorHistory = new DashboardHistory(zkCluster.getZkClusterKey(),
				DashboardServiceImpl.DashboardType.EXECUTOR.name(),
				DashboardServiceImpl.DashboardTopic.EXECUTOR_COUNT.name(), JSON.toJSONString(executorContent),
				currentDate);
		dashboardHistories.add(executorHistory);
	}

	private void calculateRunTimes(StatisticsModel statisticsModel, ZkCluster zkCluster,
			List<DashboardHistory> dashboardHistories, Date currentDate) {
		long successCount = statisticsModel.getZkClusterDailyCountAnalyzer().getTotalCount();
		long failCount = statisticsModel.getZkClusterDailyCountAnalyzer().getErrorCount();
		Map<String, Long> content = new HashMap<>(2);
		content.put("count", successCount);
		content.put("failCount", failCount);
		DashboardHistory allDomainHistory = new DashboardHistory(zkCluster.getZkClusterKey(),
				DashboardServiceImpl.DashboardType.DOMAIN.name(),
				DashboardServiceImpl.DashboardTopic.DOMAIN_OVERALL_COUNT.name(), JSON.toJSONString(content),
				currentDate);
		dashboardHistories.add(allDomainHistory);
	}

	private void forwardDashboardRefreshToRemote(String zkClusterKey) throws SaturnJobConsoleException {
		CloseableHttpClient httpClient = null;
		String url = null;
		try {
			String domain = ZkClusterMappingUtils.getConsoleDomainByZkClusterKey(systemConfigService, zkClusterKey);
			if (StringUtils.isBlank(domain)) {
				throw new SaturnJobConsoleException(
						String.format("The console domain is not found by zkClusterKey(%s)", zkClusterKey));
			}
			url = domain + "/rest/v1/dashboard/refresh?zkClusterKey=" + zkClusterKey;
			httpClient = HttpClientBuilder.create().build();
			HttpPost httpPost = createHttpRequest(url);
			CloseableHttpResponse httpResponse = httpClient.execute(httpPost);
			handleResponse(url, httpResponse);
		} catch (SaturnJobConsoleException se) {
			throw se;
		} catch (Exception e) {
			throw new SaturnJobConsoleException("Fail to execute forwardDashboardRefreshToRemote, Url: " + url, e);
		} finally {
			IOUtils.closeQuietly(httpClient);
		}
	}

	private void handleResponse(String url, CloseableHttpResponse httpResponse)
			throws IOException, SaturnJobConsoleException {
		StatusLine statusLine = httpResponse.getStatusLine();
		Integer statusCode = statusLine != null ? statusLine.getStatusCode() : null;
		log.info("the statusCode of remote request is:" + statusCode);
		if (statusLine != null && statusCode == HttpStatus.SC_OK) {
			String takeTime = IOUtils.toString(httpResponse.getEntity().getContent(), "UTF-8");
			log.info("forwardDashboardRefreshToRemote Url " + url + ", spend time:" + takeTime);
			return;
		}
		if (statusCode >= HttpStatus.SC_BAD_REQUEST && statusCode <= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
			String responseBody = EntityUtils.toString(httpResponse.getEntity());
			if (StringUtils.isNotBlank(responseBody)) {
				String errMsg = JSONObject.parseObject(responseBody).getString("message");
				throw new SaturnJobConsoleException(errMsg);
			} else {
				throw new SaturnJobConsoleException("internal server error");
			}
		} else {
			throw new SaturnJobConsoleException("unexpected status returned from Saturn Server.");
		}
	}

	private HttpPost createHttpRequest(String url) {
		HttpPost httpPost = new HttpPost(url);
		RequestConfig cfg = RequestConfig.custom().setConnectTimeout(CONNECT_TIMEOUT_MS).setSocketTimeout(SO_TIMEOUT_MS)
				.build();
		httpPost.setConfig(cfg);
		return httpPost;
	}

	private void refreshStatistics2DB(ZkCluster zkCluster) {
		log.info("start refresh statistics by zkClusterKey:{}", zkCluster.getZkClusterKey());
		Date start = new Date();
		StatisticsModel statisticsModel = initStatisticsModel();
		List<Callable<Boolean>> callableList = getStatCallableList(zkCluster, statisticsModel);
		try {
			if (callableList != null && !callableList.isEmpty()) {
				statExecutorService.invokeAll(callableList);
			}
			statisticsPersistence.persist(statisticsModel, zkCluster);
			postRefreshStatistics2DB(statisticsModel, zkCluster);
		} catch (InterruptedException e) {
			log.warn("the refreshStatistics2DB thread is interrupted", e);
			Thread.currentThread().interrupt();
		}
		log.info("end refresh statistics by zkClusterKey:{}, takes {}", zkCluster.getZkClusterKey(),
				new Date().getTime() - start.getTime());
	}

	private List<Callable<Boolean>> getStatCallableList(final ZkCluster zkCluster,
			final StatisticsModel statisticsModel) {
		List<Callable<Boolean>> callableList = Lists.newArrayList();
		for (final RegistryCenterConfiguration config : zkCluster.getRegCenterConfList()) {
			// 过滤非当前zk连接
			if (!zkCluster.getZkAddr().equals(config.getZkAddressList())) {
				continue;
			}
			Callable<Boolean> callable = new Callable<Boolean>() {
				@Override
				public Boolean call() throws Exception {
					return analyzeStatistics(statisticsModel, zkCluster, config);
				}
			};
			callableList.add(callable);
		}
		return callableList;
	}

	private boolean analyzeStatistics(StatisticsModel statisticsModel, ZkCluster zkCluster,
			RegistryCenterConfiguration config) {
		String namespace = config.getNamespace();
		try {
			DomainStatistics domain = statisticsModel.getDomainStatisticsAnalyzer().initDomain(zkCluster, config);
			CuratorRepository.CuratorFrameworkOp curatorFrameworkOp = registryCenterService
					.getCuratorFrameworkOp(namespace);
			List<AbnormalJob> oldAbnormalJobs = getOldAbnormalJobs(zkCluster);
			List<Timeout4AlarmJob> oldTimeout4AlarmJobs = getOldTimeout4AlarmJobs(zkCluster);
			List<DisabledTimeoutAlarmJob> disabledTimeoutAlarmJobs = getOldDisabledTimeoutJobs(zkCluster);
			statisticsModel.analyzeExecutor(curatorFrameworkOp, config);
			List<String> jobs = jobService.getUnSystemJobNames(config.getNamespace());
			for (String job : jobs) {
				if (!curatorFrameworkOp.checkExists(JobNodePath.getConfigNodePath(job))) {
					continue;
				}

				try {
					Boolean localMode = Boolean
							.valueOf(curatorFrameworkOp.getData(JobNodePath.getConfigNodePath(job, "localMode")));
					JobStatistics jobStatistics = statisticsModel
							.analyzeJobStatistics(curatorFrameworkOp, job, localMode, config);
					String jobDegree = String.valueOf(jobStatistics.getJobDegree());
					statisticsModel.analyzeShardingCount(curatorFrameworkOp, domain);
					if (!localMode) {// 非本地作业才参与判断
						statisticsModel.analyzeOutdatedNoRunningJob(curatorFrameworkOp, oldAbnormalJobs, job, jobDegree,
								config);
					}
					statisticsModel
							.analyzeTimeout4AlarmJob(curatorFrameworkOp, oldTimeout4AlarmJobs, job, jobDegree, config);
					statisticsModel.analyzeUnableFailoverJob(curatorFrameworkOp, job, jobDegree, config);
					statisticsModel.analyzeDisabledTimeoutJob(curatorFrameworkOp, disabledTimeoutAlarmJobs, job,
							jobDegree, config);
				} catch (Exception e) {
					log.info(String.format("analyzeStatistics namespace(%s) jobName(%s) error", namespace, job), e);
				}
			}
			statisticsModel.analyzeProcessCount(domain, jobs, config);
		} catch (Exception e) {
			log.info(String.format("analyzeStatistics namespace(%s) error", namespace), e);
			return false;
		}
		return true;
	}

	private List<AbnormalJob> getOldAbnormalJobs(ZkCluster zkCluster) {
		SaturnStatistics saturnStatistics = saturnStatisticsService
				.findStatisticsByNameAndZkList(StatisticsTableKeyConstant.UNNORMAL_JOB, zkCluster.getZkAddr());
		List<AbnormalJob> oldAbnormalJobs = new ArrayList<>();
		if (saturnStatistics != null) {
			String result = saturnStatistics.getResult();
			if (StringUtils.isNotBlank(result)) {
				oldAbnormalJobs = JSON.parseArray(result, AbnormalJob.class);
			}
		}
		return oldAbnormalJobs;
	}

	private List<Timeout4AlarmJob> getOldTimeout4AlarmJobs(ZkCluster zkCluster) {
		SaturnStatistics saturnStatistics = saturnStatisticsService
				.findStatisticsByNameAndZkList(StatisticsTableKeyConstant.TIMEOUT_4_ALARM_JOB, zkCluster.getZkAddr());
		List<Timeout4AlarmJob> oldTimeout4AlarmJobs = new ArrayList<>();
		if (saturnStatistics != null) {
			String result = saturnStatistics.getResult();
			if (StringUtils.isNotBlank(result)) {
				oldTimeout4AlarmJobs = JSON.parseArray(result, Timeout4AlarmJob.class);
			}
		}
		return oldTimeout4AlarmJobs;
	}

	private List<DisabledTimeoutAlarmJob> getOldDisabledTimeoutJobs(ZkCluster zkCluster) {
		SaturnStatistics saturnStatistics = saturnStatisticsService
				.findStatisticsByNameAndZkList(StatisticsTableKeyConstant.DISABLED_TIMEOUT_JOB, zkCluster.getZkAddr());
		List<DisabledTimeoutAlarmJob> disabledTimeoutAlarmJobs = new ArrayList<>();
		if (saturnStatistics != null) {
			String result = saturnStatistics.getResult();
			if (StringUtils.isNotBlank(result)) {
				disabledTimeoutAlarmJobs = JSON.parseArray(result, DisabledTimeoutAlarmJob.class);
			}
		}
		return disabledTimeoutAlarmJobs;
	}

	protected StatisticsModel initStatisticsModel() {
		StatisticsModel statisticsModel = new StatisticsModel();
		ExecutorInfoAnalyzer executorInfoAnalyzer = new ExecutorInfoAnalyzer();
		statisticsModel.setExecutorInfoAnalyzer(executorInfoAnalyzer);

		OutdatedNoRunningJobAnalyzer outdatedNoRunningJobAnalyzer = new OutdatedNoRunningJobAnalyzer();
		outdatedNoRunningJobAnalyzer.setAbnormalShardingStateCache(abnormalShardingStateCache);
		outdatedNoRunningJobAnalyzer.setReportAlarmService(reportAlarmService);
		outdatedNoRunningJobAnalyzer.setJobService(jobService);
		statisticsModel.setOutdatedNoRunningJobAnalyzer(outdatedNoRunningJobAnalyzer);

		UnableFailoverJobAnalyzer unableFailoverJobAnalyzer = new UnableFailoverJobAnalyzer();
		unableFailoverJobAnalyzer.setJobService(jobService);
		statisticsModel.setUnableFailoverJobAnalyzer(unableFailoverJobAnalyzer);

		Timeout4AlarmJobAnalyzer timeout4AlarmJobAnalyzer = new Timeout4AlarmJobAnalyzer();
		timeout4AlarmJobAnalyzer.setReportAlarmService(reportAlarmService);
		statisticsModel.setTimeout4AlarmJobAnalyzer(timeout4AlarmJobAnalyzer);

		DisabledTimeoutJobAnalyzer disabledTimeoutJobAnalyzer = new DisabledTimeoutJobAnalyzer();
		disabledTimeoutJobAnalyzer.setReportAlarmService(reportAlarmService);
		statisticsModel.setDisabledTimeoutJobAnalyzer(disabledTimeoutJobAnalyzer);

		JobStatisticsAnalyzer jobStatisticsAnalyzer = new JobStatisticsAnalyzer();
		statisticsModel.setJobStatisticsAnalyzer(jobStatisticsAnalyzer);

		DomainStatisticsAnalyzer domainStatisticsAnalyzer = new DomainStatisticsAnalyzer();
		statisticsModel.setDomainStatisticsAnalyzer(domainStatisticsAnalyzer);

		ZkClusterDailyCountAnalyzer zkClusterDailyCountAnalyzer = new ZkClusterDailyCountAnalyzer();
		statisticsModel.setZkClusterDailyCountAnalyzer(zkClusterDailyCountAnalyzer);
		return statisticsModel;
	}

}