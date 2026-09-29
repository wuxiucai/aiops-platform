package com.aiops.schedule;

import com.aiops.module.log.service.LogTemplateJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Drain 模板提取 Job：每 10min 一次。
 */
@Slf4j
@Component
public class LogTemplateJob {

    private final LogTemplateJobService logTemplateJobService;

    public LogTemplateJob(LogTemplateJobService logTemplateJobService) {
        this.logTemplateJobService = logTemplateJobService;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void run() {
        try {
            Map<String, Object> r = logTemplateJobService.runTemplateJob(10);
            log.info("[Job] LogTemplateJob result={}", r);
        } catch (Exception e) {
            log.error("[Job] LogTemplateJob failed: {}", e.getMessage(), e);
        }
    }
}
