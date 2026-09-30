package com.aiops.module.llm.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5-4：log 模板统计组装器 — 与 v2 §6.3B 完全一致格式的输出。
 */
class LogTemplateSummaryBuilderTest {

    @Test
    void matchesV2Format() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 30, 9, 0, 0);
        LocalDateTime end   = LocalDateTime.of(2026, 9, 30, 10, 0, 0);

        List<LogTemplateSummaryBuilder.TemplateStat> stats = List.of(
                new LogTemplateSummaryBuilder.TemplateStat(12, "FAULT-INJECT 业务异常 PAYMENT-GATEWAY-5XX ...",
                        "ERROR", 300, 5, 295, "order-service"),
                new LogTemplateSummaryBuilder.TemplateStat(13, "FAULT-INJECT error-log fired pattern <*> <*> <*>",
                        "WARN", 600, 100, 500, "order-service")
        );
        List<LogTemplateSummaryBuilder.NewTemplate> news = List.of(
                new LogTemplateSummaryBuilder.NewTemplate(270, "FAULT-INJECT 业务异常 BREAKER-OPEN ...",
                        "ERROR", end.minusMinutes(30), 300)
        );
        List<LogTemplateSummaryBuilder.RelatedAlert> alerts = List.of(
                new LogTemplateSummaryBuilder.RelatedAlert(100, "spike", "CRITICAL",
                        "模板爆量：FAULT-INJECT", end.minusMinutes(20))
        );

        String out = LogTemplateSummaryBuilder.build(start, end,
                12_000, 346,
                LogTemplateSummaryBuilder.levelDistributionOf(stats),
                stats, news, alerts);

        // 重要节段
        assertTrue(out.contains("【时间范围】2026-09-30 09:00:00 ~ 2026-09-30 10:00:00"));
        assertTrue(out.contains("【日志概况】总量：12000 模板数：346"));
        assertTrue(out.contains("级别分布：WARN=600, ERROR=300"));
        assertTrue(out.contains("【模板统计】"));
        assertTrue(out.contains("id=12 [ERROR]"));
        assertTrue(out.contains("count=300  上期=5  变化=+295"));
        assertTrue(out.contains("【新增模板】"));
        assertTrue(out.contains("id=270 [ERROR]"));
        assertTrue(out.contains("【关联告警】"));
        assertTrue(out.contains("#100 [CRITICAL/spike]"));

        // 论可验收的时候可直接打印
        System.out.println(out);
    }

    @Test
    void emptySections() {
        String out = LogTemplateSummaryBuilder.build(
                LocalDateTime.of(2026, 9, 30, 9, 0, 0),
                LocalDateTime.of(2026, 9, 30, 10, 0, 0),
                0, 0, "INFO=0", List.of(), List.of(), List.of());
        assertTrue(out.contains("(无)"));
    }

    @Test
    void levelDistributionSortedByCount() {
        List<LogTemplateSummaryBuilder.TemplateStat> stats = List.of(
                new LogTemplateSummaryBuilder.TemplateStat(1, "a", "INFO", 5, 0, 0, null),
                new LogTemplateSummaryBuilder.TemplateStat(2, "b", "ERROR", 100, 0, 0, null),
                new LogTemplateSummaryBuilder.TemplateStat(3, "c", "WARN", 30, 0, 0, null)
        );
        String s = LogTemplateSummaryBuilder.levelDistributionOf(stats);
        assertTrue(s.startsWith("ERROR="), "must sort by count desc: " + s);
        assertTrue(s.indexOf("ERROR=") < s.indexOf("WARN="));
        assertTrue(s.indexOf("WARN=") < s.indexOf("INFO="));
    }
}
