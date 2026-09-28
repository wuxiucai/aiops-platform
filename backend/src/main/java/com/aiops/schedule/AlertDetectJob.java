package com.aiops.schedule;

import com.aiops.module.alert.entity.AlertRule;
import com.aiops.module.alert.mapper.AlertRuleMapper;
import com.aiops.module.alert.service.AlertDetectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警检测 Job：每 30s 扫描一次启用规则，逐条提交到 aiopsDetectExecutor。
 */
@Slf4j
@Component
public class AlertDetectJob {

    private final AlertRuleMapper alertRuleMapper;
    private final AlertDetectService alertDetectService;
    private final ThreadPoolTaskExecutor detectExecutor;

    public AlertDetectJob(AlertRuleMapper alertRuleMapper,
                          AlertDetectService alertDetectService,
                          @Qualifier("aiopsDetectExecutor") ThreadPoolTaskExecutor detectExecutor) {
        this.alertRuleMapper = alertRuleMapper;
        this.alertDetectService = alertDetectService;
        this.detectExecutor = detectExecutor;
    }

    /** 每 30s 一次：扫描启用规则 → 异步逐条检测 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void run() {
        long start = System.currentTimeMillis();
        log.info("[Job] AlertDetectJob start");
        List<AlertRule> rules = alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getEnabled, 1));
        AtomicInteger triggered = new AtomicInteger(0);
        for (AlertRule rule : rules) {
            detectExecutor.execute(() -> {
                try {
                    if (alertDetectService.processRule(rule.getId())) {
                        triggered.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("[Job] AlertDetectJob 规则#{} 执行失败: {}", rule.getId(), e.getMessage(), e);
                }
            });
        }
        log.info("[Job] AlertDetectJob end, cost={}ms, triggered={}",
                System.currentTimeMillis() - start, triggered.get());
    }
}
