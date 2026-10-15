package com.aiops.schedule;

import com.aiops.module.monitor.entity.CollectTask;
import com.aiops.module.monitor.service.MetricCollectService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标采集 Job：扫描启用任务 → 提交到 aiopsCollectExecutor。
 */
@Slf4j
@Component
public class MetricCollectJob {

    private final MetricCollectService metricCollectService;
    private final ThreadPoolTaskExecutor collectExecutor;

    /**
     * 在途任务 id。last_run_time 要等采集完成才写库，单次采集耗时超过扫描节拍（1s）时，
     * 仅凭 last_run_time 判断会把同一任务重复提交，造成并发采集与指标重复入库。
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public MetricCollectJob(MetricCollectService metricCollectService,
                            @Qualifier("aiopsCollectExecutor") ThreadPoolTaskExecutor collectExecutor) {
        this.metricCollectService = metricCollectService;
        this.collectExecutor = collectExecutor;
    }

    /**
     * 扫描节拍 1s，任务自身 interval_sec 决定是否到点执行。
     *
     * 过去是 15s 节拍，最小调度粒度 15s。导致用户在 collect_task.interval_sec
     * 配置了 1/3/5s 等小于 15s 的档位时实际不生效（仍按 15s 走）。
     * 现在节拍 1s，支持 1s/5s/15s/30s/60s/300s 全部档位。
     *
     * 性能说明：
     *   - 每 1s 一次 SELECT collect_task WHERE status=1 AND deleted=0，
     *     表通常 < 100 行，毫秒级返回，对 DB 压力可忽略。
     *   - 采集在 collectExecutor 线程池异步执行；inFlight 保证同一任务同一时刻最多一个在跑。
     */
    @Scheduled(fixedDelay = 1_000, initialDelay = 10_000)
    public void run() {
        long start = System.currentTimeMillis();
        List<CollectTask> tasks = metricCollectService.listEnabledTasks();
        long now = System.currentTimeMillis();
        int submitted = 0;
        for (CollectTask task : tasks) {
            long intervalMs = (task.getIntervalSec() == null ? 30 : task.getIntervalSec()) * 1000L;
            boolean due = task.getLastRunTime() == null
                    || now - java.sql.Timestamp.valueOf(task.getLastRunTime()).getTime() >= intervalMs;
            if (due && inFlight.add(task.getId())) {
                try {
                    collectExecutor.execute(() -> {
                        try {
                            metricCollectService.runTask(task);
                        } catch (Exception e) {
                            log.error("[Job] 采集任务执行失败: taskId={}", task.getId(), e);
                        } finally {
                            inFlight.remove(task.getId());
                        }
                    });
                    submitted++;
                } catch (RuntimeException rejected) {
                    // 线程池拒绝时任务从未运行，必须释放标记，否则该任务永远不再被调度
                    inFlight.remove(task.getId());
                    log.warn("[Job] 采集任务提交被拒绝: taskId={}, {}", task.getId(), rejected.getMessage());
                }
            }
        }
        // 只在有提交时打 INFO；空扫降为 DEBUG，避免 1s 节拍导致日志噪音
        if (submitted > 0) {
            log.info("[Job] MetricCollectJob submitted={}, cost={}ms", submitted, System.currentTimeMillis() - start);
        } else if (log.isDebugEnabled()) {
            log.debug("[Job] MetricCollectJob idle, cost={}ms", System.currentTimeMillis() - start);
        }
    }
}
