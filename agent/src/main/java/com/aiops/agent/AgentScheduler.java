package com.aiops.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时采集 + 定时心跳（单线程 lucent schedules
 * （语义 windows版一样可用） ）。
 */
@Component
public class AgentScheduler implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final OshiCollector collector;
    private final Reporter reporter;
    private final AgentConfig cfg;
    private ScheduledExecutorService scheduler;

    public AgentScheduler(OshiCollector collector, Reporter reporter, AgentConfig cfg) {
        this.collector = collector;
        this.reporter = reporter;
        this.cfg = cfg;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("[agent] starting scheduler collect={}s heartbeat={}s platform={} targetId={}",
                cfg.getCollectIntervalSec(), cfg.getHeartbeatIntervalSec(),
                cfg.getPlatformUrl(), cfg.getTargetId());
        scheduler = Executors.newScheduledThreadPool(2);

        // 采集 + 上报
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                List<Map<String, Object>> batch = collector.collect();
                reporter.reportMetrics(batch);
            } catch (Throwable t) {
                log.warn("[agent] collect fail: {}", t.getMessage());
            }
        }, 5, cfg.getCollectIntervalSec(), TimeUnit.SECONDS);

        // 心跳
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                reporter.heartbeat();
            } catch (Throwable t) {
                log.warn("[agent] heartbeat fail: {}", t.getMessage());
            }
        }, 10, cfg.getHeartbeatIntervalSec(), TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("[agent] stopped");
    }
}
