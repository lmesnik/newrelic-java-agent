/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.service;

import com.newrelic.InfiniteTracing;
import com.newrelic.InfiniteTracingConfig;
import com.newrelic.agent.Agent;
import com.newrelic.agent.AgentConnectionEstablishedListener;
import com.newrelic.agent.ExpirationService;
import com.newrelic.agent.GCService;
import com.newrelic.agent.HarvestService;
import com.newrelic.agent.HarvestServiceImpl;
import com.newrelic.agent.RPMServiceManager;
import com.newrelic.agent.RPMServiceManagerImpl;
import com.newrelic.agent.ThreadService;
import com.newrelic.agent.TracerService;
import com.newrelic.agent.TransactionService;
import com.newrelic.agent.attributes.AttributesService;
import com.newrelic.agent.browser.BrowserService;
import com.newrelic.agent.browser.BrowserServiceImpl;
import com.newrelic.agent.cache.CacheService;
import com.newrelic.agent.circuitbreaker.CircuitBreakerService;
import com.newrelic.agent.commands.CommandParser;
import com.newrelic.agent.config.ConfigService;
import com.newrelic.agent.core.CoreService;
import com.newrelic.agent.database.DatabaseService;
import com.newrelic.agent.deadlock.DeadlockDetectorService;
import com.newrelic.agent.environment.EnvironmentService;
import com.newrelic.agent.environment.EnvironmentServiceImpl;
import com.newrelic.agent.extension.ExtensionService;
import com.newrelic.agent.instrumentation.ClassTransformerService;
import com.newrelic.agent.instrumentation.ClassTransformerServiceImpl;
import com.newrelic.agent.jmx.JmxService;
import com.newrelic.agent.language.SourceLanguageService;
import com.newrelic.agent.normalization.NormalizationService;
import com.newrelic.agent.normalization.NormalizationServiceImpl;
import com.newrelic.agent.profile.ProfilerService;
import com.newrelic.agent.reinstrument.RemoteInstrumentationService;
import com.newrelic.agent.reinstrument.RemoteInstrumentationServiceImpl;
import com.newrelic.agent.rpm.RPMConnectionService;
import com.newrelic.agent.rpm.RPMConnectionServiceImpl;
import com.newrelic.agent.samplers.CPUSamplerService;
import com.newrelic.agent.samplers.NoopSamplerService;
import com.newrelic.agent.samplers.SamplerService;
import com.newrelic.agent.samplers.SamplerServiceImpl;
import com.newrelic.agent.service.analytics.InfiniteTracingEnabledCheck;
import com.newrelic.agent.service.analytics.InsightsService;
import com.newrelic.agent.service.analytics.InsightsServiceImpl;
import com.newrelic.agent.service.analytics.SpanEventCreationDecider;
import com.newrelic.agent.service.analytics.SpanEventsService;
import com.newrelic.agent.service.analytics.TransactionDataToDistributedTraceIntrinsics;
import com.newrelic.agent.service.analytics.TransactionEventsService;
import com.newrelic.agent.service.async.AsyncTransactionService;
import com.newrelic.agent.service.module.JarCollectorService;
import com.newrelic.agent.service.module.JarCollectorServiceImpl;
import com.newrelic.agent.sql.SqlTraceService;
import com.newrelic.agent.sql.SqlTraceServiceImpl;
import com.newrelic.agent.stats.StatsEngine;
import com.newrelic.agent.stats.StatsService;
import com.newrelic.agent.stats.StatsServiceImpl;
import com.newrelic.agent.stats.StatsWork;
import com.newrelic.agent.trace.TransactionTraceService;
import com.newrelic.agent.tracing.DistributedTraceService;
import com.newrelic.agent.tracing.DistributedTraceServiceImpl;
import com.newrelic.agent.utilization.UtilizationService;
import com.newrelic.api.agent.MetricAggregator;
import com.newrelic.api.agent.NewRelic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service Manager implementation
 *
 * This class is thread-safe.
 */
public class ServiceManagerImpl extends AbstractService implements ServiceManager {

    private final ConcurrentMap<String, Service> services = new ConcurrentHashMap<>();
    private final CoreService coreService;
    private final ConfigService configService;

    private volatile ExtensionService extensionService;
    private volatile ProfilerService profilerService;
    private volatile TracerService tracerService;
    private volatile TransactionTraceService transactionTraceService;
    private volatile ThreadService threadService;
    private volatile HarvestService harvestService;
    private volatile Service gcService;
    private volatile TransactionService transactionService;
    private volatile JmxService jmxService;
    private volatile TransactionEventsService transactionEventsService;
    private volatile CommandParser commandParser;
    private volatile RPMServiceManager rpmServiceManager;
    private volatile Service cpuSamplerService;
    private volatile DeadlockDetectorService deadlockDetectorService;
    private volatile SamplerService samplerService;
    private volatile RPMConnectionService rpmConnectionService;
    private volatile EnvironmentService environmentService;
    private volatile ClassTransformerService classTransformerService;
    private volatile StatsService statsService = new InitialStatsService();
    private volatile SqlTraceService sqlTraceService;
    private volatile DatabaseService databaseService;
    private volatile BrowserService browserService;
    private volatile JarCollectorService jarCollectorService;
    private volatile CacheService cacheService;
    private volatile NormalizationService normalizationService;
    private volatile RemoteInstrumentationService remoteInstrumentationService;
    private volatile AttributesService attsService;
    private volatile UtilizationService utilizationService;
    private volatile InsightsService insightsService;
    private volatile AsyncTransactionService asyncTxService;
    private volatile CircuitBreakerService circuitBreakerService;
    private volatile DistributedTraceServiceImpl distributedTraceService;
    private volatile SpanEventsService spanEventsService;
    private volatile SourceLanguageService sourceLanguageService;
    private volatile ExpirationService expirationService;

    public ServiceManagerImpl(CoreService coreService, ConfigService configService) {
        super(ServiceManagerImpl.class.getSimpleName());
        this.coreService = coreService;
        this.configService = configService;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    protected synchronized void doStart() throws Exception {
        // The ConfigService has been created, but not started. This means it
        // is safe to consult the config, but it will be the local config only.
        coreService.start();

        threadService = new ThreadService();
        circuitBreakerService = new CircuitBreakerService();
        classTransformerService = new ClassTransformerServiceImpl(coreService.getInstrumentation());
        jmxService = new JmxService();
        extensionService = new ExtensionService(configService);
        jarCollectorService = new JarCollectorServiceImpl();
        sourceLanguageService = new SourceLanguageService();
        expirationService = new ExpirationService();

        tracerService = new TracerService();
        // this allows async parts of transaction to be registered
        // it must be created before the first class transformation occurs
        asyncTxService = new AsyncTransactionService();
        // this is called in a transaction finish - it needs to be created before the first class transformation
        environmentService = new EnvironmentServiceImpl();

        /*
         * Before this point the ClassTransformer is not initialized, so be careful not to load classes that should be
         * instrumented. In particular, an ExecutorService should not be created because it causes the classes
         * instrumented by ConcurrentCallablePointCut to be loaded prematurely.
         */
        cacheService = new CacheService();
        extensionService.start();
        classTransformerService.start();

        boolean realAgent = coreService.getInstrumentation() != null;

        statsService = new StatsServiceImpl();
        replayStartupStatsWork();

        utilizationService = new UtilizationService();
        // Start as early as possible.
        if (realAgent) {
            utilizationService.start();
        }

        rpmConnectionService = new RPMConnectionServiceImpl();
        transactionService = new TransactionService();

        InfiniteTracing infiniteTracing = buildInfiniteTracing(configService);
        InfiniteTracingEnabledCheck infiniteTracingEnabledCheck = new InfiniteTracingEnabledCheck(configService);
        SpanEventCreationDecider spanEventCreationDecider = new SpanEventCreationDecider(configService);
        AgentConnectionEstablishedListener agentConnectionEstablishedListener = new UpdateInfiniteTracingAfterConnect(infiniteTracingEnabledCheck, infiniteTracing);

        distributedTraceService = new DistributedTraceServiceImpl();
        TransactionDataToDistributedTraceIntrinsics transactionDataToDistributedTraceIntrinsics =
                new TransactionDataToDistributedTraceIntrinsics(distributedTraceService);

        rpmServiceManager = new RPMServiceManagerImpl(agentConnectionEstablishedListener);
        normalizationService = new NormalizationServiceImpl();
        harvestService = new HarvestServiceImpl();
        gcService = realAgent ? new GCService() : new NoopService("GC Service");
        transactionTraceService = new TransactionTraceService();
        transactionEventsService = new TransactionEventsService(transactionDataToDistributedTraceIntrinsics);
        profilerService = new ProfilerService();
        commandParser = new CommandParser();
        cpuSamplerService = realAgent ? new CPUSamplerService() : new NoopService("CPU Sampler");
        deadlockDetectorService = new DeadlockDetectorService();
        samplerService = realAgent ? new SamplerServiceImpl() : new NoopSamplerService();
        sqlTraceService = new SqlTraceServiceImpl();
        databaseService = new DatabaseService();
        browserService = new BrowserServiceImpl();
        remoteInstrumentationService = new RemoteInstrumentationServiceImpl();
        attsService = new AttributesService();
        insightsService = new InsightsServiceImpl();
        spanEventsService = SpanEventsServiceFactory.builder()
                .configService(configService)
                .rpmServiceManager(rpmServiceManager)
                .transactionService(transactionService)
                .infiniteTracingConsumer(infiniteTracing)
                .spanEventCreationDecider(spanEventCreationDecider)
                .environmentService(environmentService)
                .transactionDataToDistributedTraceIntrinsics(transactionDataToDistributedTraceIntrinsics)
                .build();

        // Register harvest listeners that started before harvest service was created.
        harvestService.addHarvestListener(extensionService);

        asyncTxService.start();
        threadService.start();
        statsService.start();
        environmentService.start();
        rpmConnectionService.start();
        tracerService.start();
        jarCollectorService.start();
        sourceLanguageService.start();
        harvestService.start();
        gcService.start();
        transactionService.start();
        transactionTraceService.start();
        transactionEventsService.start();
        profilerService.start();
        commandParser.start();
        jmxService.start();
        cpuSamplerService.start();
        deadlockDetectorService.start();
        samplerService.start();
        sqlTraceService.start();
        browserService.start();
        cacheService.start();
        normalizationService.start();
        databaseService.start();
        configService.start();
        remoteInstrumentationService.start();
        attsService.start();
        insightsService.start();
        circuitBreakerService.start();
        distributedTraceService.start();
        spanEventsService.start();

        startServices();

        // start last so other services can add connection listeners
        rpmServiceManager.start();

        // used for debugging purposes to quickly determine slow service startups
        ServiceTiming.setEndTime();
        ServiceTiming.logServiceTimings(getLogger());
    }

    private InfiniteTracing buildInfiniteTracing(ConfigService configService) {
        com.newrelic.agent.config.InfiniteTracingConfig config = configService.getDefaultAgentConfig().getInfiniteTracingConfig();
        Double flakyPercentage = configService.getDefaultAgentConfig().getInfiniteTracingConfig().getFlakyPercentage();
        boolean usePlaintext = configService.getDefaultAgentConfig().getInfiniteTracingConfig().getUsePlaintext();

        InfiniteTracingConfig infiniteTracingConfig = InfiniteTracingConfig.builder()
                .maxQueueSize(config.getSpanEventsQueueSize())
                .logger(Agent.LOG.getChildLogger("com.newrelic.infinite_tracing"))
                .host(config.getTraceObserverHost())
                .port(config.getTraceObserverPort())
                .licenseKey(configService.getDefaultAgentConfig().getLicenseKey())
                .flakyPercentage(flakyPercentage)
                .usePlaintext(usePlaintext)
                .build();

        return InfiniteTracing.initialize(infiniteTracingConfig, NewRelic.getAgent().getMetricAggregator());
    }

    @Override
    protected synchronized void doStop() throws Exception {
        insightsService.stop();
        circuitBreakerService.stop();
        remoteInstrumentationService.stop();
        configService.stop();
        classTransformerService.stop();
        coreService.stop();
        rpmConnectionService.stop();
        deadlockDetectorService.stop();
        jarCollectorService.stop();
        harvestService.stop();
        cpuSamplerService.stop();
        samplerService.stop();
        sqlTraceService.stop();
        normalizationService.stop();
        databaseService.stop();
        extensionService.stop();
        transactionService.stop();
        tracerService.stop();
        threadService.stop();
        transactionTraceService.stop();
        transactionEventsService.stop();
        profilerService.stop();
        commandParser.stop();
        jmxService.stop();
        rpmServiceManager.stop();
        environmentService.stop();
        statsService.stop();
        browserService.stop();
        cacheService.stop();
        attsService.stop();
        sourceLanguageService.stop();
        utilizationService.stop();
        asyncTxService.stop();
        gcService.stop();
        distributedTraceService.stop();
        spanEventsService.stop();
        stopServices();
    }

    /**
     * Start dynamic services.
     */
    private void startServices() throws Exception {
        for (Service service : services.values()) {
            service.start();
        }
    }

    /**
     * Stop dynamic services.
     */
    private void stopServices() throws Exception {
        for (Service service : services.values()) {
            service.stop();
        }
    }

    @Override
    public void addService(Service service) {
        services.put(service.getName(), service);
    }

    @Override
    public Map<String, Map<String, Object>> getServicesConfiguration() {
        Map<String, Map<String, Object>> config = new HashMap<>();

        Map<String, Object> serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", transactionService.isEnabled());
        config.put("TransactionService", serviceInfo);

        serviceInfo = new HashMap<>();
        config.put("TransactionTraceService", serviceInfo);
        serviceInfo.put("enabled", transactionTraceService.isEnabled());

        serviceInfo = new HashMap<>();
        config.put("TransactionEventsService", serviceInfo);
        serviceInfo.put("enabled", transactionEventsService.isEnabled());

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", profilerService.isEnabled());
        config.put("ProfilerService", serviceInfo);

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", tracerService.isEnabled());
        config.put("TracerService", serviceInfo);

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", commandParser.isEnabled());
        config.put("CommandParser", serviceInfo);

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", jmxService.isEnabled());
        config.put("JmxService", serviceInfo);

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", threadService.isEnabled());
        config.put("ThreadService", serviceInfo);

        serviceInfo = new HashMap<>();
        serviceInfo.put("enabled", deadlockDetectorService.isEnabled());
        config.put("DeadlockService", serviceInfo);

        for (Service service : services.values()) {
            serviceInfo = new HashMap<>();
            serviceInfo.put("enabled", service.isEnabled());
            config.put(service.getClass().getSimpleName(), serviceInfo);
        }

        return config;
    }

    @Override
    public Service getService(String name) {
        return services.get(name);
    }

    @Override
    public ExtensionService getExtensionService() {
        return extensionService;
    }

    @Override
    public ProfilerService getProfilerService() {
        return profilerService;
    }

    @Override
    public TracerService getTracerService() {
        return tracerService;
    }

    @Override
    public TransactionTraceService getTransactionTraceService() {
        return transactionTraceService;
    }

    @Override
    public ThreadService getThreadService() {
        return threadService;
    }

    @Override
    public HarvestService getHarvestService() {
        return harvestService;
    }

    @Override
    public TransactionService getTransactionService() {
        return transactionService;
    }

    @Override
    public JmxService getJmxService() {
        return jmxService;
    }

    @Override
    public TransactionEventsService getTransactionEventsService() {
        return transactionEventsService;
    }

    @Override
    public CommandParser getCommandParser() {
        return commandParser;
    }

    @Override
    public RPMServiceManager getRPMServiceManager() {
        return rpmServiceManager;
    }

    @Override
    public SamplerService getSamplerService() {
        return samplerService;
    }

    @Override
    public CoreService getCoreService() {
        return coreService;
    }

    @Override
    public ConfigService getConfigService() {
        return configService;
    }

    @Override
    public RPMConnectionService getRPMConnectionService() {
        return rpmConnectionService;
    }

    @Override
    public EnvironmentService getEnvironmentService() {
        return environmentService;
    }

    @Override
    public ClassTransformerService getClassTransformerService() {
        return classTransformerService;
    }

    @Override
    public StatsService getStatsService() {
        return statsService;
    }

    @Override
    public SqlTraceService getSqlTraceService() {
        return sqlTraceService;
    }

    @Override
    public DatabaseService getDatabaseService() {
        return databaseService;
    }

    @Override
    public CacheService getCacheService() {
        return cacheService;
    }

    @Override
    public AsyncTransactionService getAsyncTxService() {
        return asyncTxService;
    }

    @Override
    public BrowserService getBrowserService() {
        return browserService;
    }

    @Override
    public NormalizationService getNormalizationService() {
        return normalizationService;
    }

    @Override
    public JarCollectorService getJarCollectorService() {
        return jarCollectorService;
    }

    @Override
    public RemoteInstrumentationService getRemoteInstrumentationService() {
        return remoteInstrumentationService;
    }

    @Override
    public AttributesService getAttributesService() {
        return attsService;
    }

    @Override
    public InsightsService getInsights() {
        return insightsService;
    }

    @Override
    public CircuitBreakerService getCircuitBreakerService() {
        return circuitBreakerService;
    }

    private void replayStartupStatsWork() {
        for (StatsWork work : statsWork) {
            statsService.doStatsWork(work);
        }
        statsWork.clear();
    }

    private final List<StatsWork> statsWork = new ArrayList<>();

    private class InitialStatsService extends AbstractService implements StatsService {
        private final MetricAggregator metricAggregator = new StatsServiceMetricAggregator(this);

        protected InitialStatsService() {
            super("Bootstrap stats service");
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void doStatsWork(StatsWork statsWork) {
            ServiceManagerImpl.this.statsWork.add(statsWork);
        }

        @Override
        public StatsEngine getStatsEngineForHarvest(String appName) {
            return null;
        }

        @Override
        protected void doStart() throws Exception {
        }

        @Override
        protected void doStop() throws Exception {
        }

        @Override
        public MetricAggregator getMetricAggregator() {
            return metricAggregator;
        }
    }

    @Override
    public UtilizationService getUtilizationService() {
        return utilizationService;
    }

    @Override
    public DistributedTraceService getDistributedTraceService() {
        return distributedTraceService;
    }

    @Override
    public SpanEventsService getSpanEventsService() {
        return spanEventsService;
    }

    @Override
    public SourceLanguageService getSourceLanguageService() {
        return sourceLanguageService;
    }

    @Override
    public ExpirationService getExpirationService() {
        return expirationService;
    }

}