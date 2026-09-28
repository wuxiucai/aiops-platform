package com.aiops.schedule;

import com.aiops.module.monitor.entity.CollectTask;
import com.aiops.module.monitor.service.MetricCollectService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指标采集 Job：扫描启用任务 → 提交到 aiopsCollectExecutor。
 */
@Slf4j
@Component
public class MetricCollectJob {

    private final MetricCollectService metricCollectService;
    private final ThreadPoolTaskExecutor collectExecutor;

    public MetricCollectJob(MetricCollectService metricCollectService,
                            @Qualifier("aiopsCollectExecutor") ThreadPoolTaskExecutor collectExecutor) {
        this.metricCollectService = metricCollectService;
        this.collectExecutor = collectExecutor;
    }

    /** 每 15s 扫描一次，任务自身 interval_sec 决定是否到点执行 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
    public void run() {
        long start = System.currentTimeMillis();
        log.info("[Job] MetricCollectJob start");
        List<CollectTask> tasks = metricCollectService.listEnabledTasks();
        long now = System.currentTimeMillis();
        int submitted = 0;
        for (CollectTask task : tasks) {
            long intervalMs = (task.getIntervalSec() == null ? 30 : task.getIntervalSec()) * 1000L;
            boolean due = task.getLastRunTime() == null
                    || now - java.sql.Timestamp.valueOf(task.getLastRunTime()).getTime() >= intervalMs;
            if (due) {
                collectExecutor.execute(() -> {
                    try {
                        metricCollectService.runTask(task);
                    } catch (Exception e) {
                        log.error("[Job] 采集任务执行失败: taskId={}", task.getId(), e);
                    }
                });
                submitted++;
            }
        }
        log.info("[Job] MetricCollectJob end, cost={}ms, submitted={}", System.currentTimeMillis() - start, submitted);
    }
}
