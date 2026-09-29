package com.aiops.schedule;

import com.aiops.module.log.service.LogTemplateJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 日志异常检测 Job：每 10min 一次，在 LogTemplateJob 之后 1min 触发。
 */
@Slf4j
@Component
public class LogDetectJob {

    private final LogTemplateJobService logTemplateJobService;

    public LogDetectJob(LogTemplateJobService logTemplateJobService) {
        this.logTemplateJobService = logTemplateJobService;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 150_000)
    public void run() {
        try {
            Map<String, Object> r = logTemplateJobService.runDetectJob(10);
            log.info("[Job] LogDetectJob result={}", r);
        } catch (Exception e) {
            log.error("[Job] LogDetectJob failed: {}", e.getMessage(), e);
        }
    }
}
