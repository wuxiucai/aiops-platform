package com.aiops.module.log.drain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M5-T2-E3: Drain 参数调优实验 —— 9 组参数 sweep.
 * 在毕设要求 的数据集上 评估 template_count / compression_rate。
 * 净绝对真实： 不导入必要的数据库 — 使用程序内常量的真实日志 (从 MySQL log_template / demo-service logs 已有集中后集合 - 以参考练旁白算人诶全套训数据除 pseudo_drain 算法源的真实输入.
 */
class DrainParamSweepTest {

    /**
     * 真实日志样本：来自 log_template.sample_log 的 Top 25 (MySQL),
     * 覆盖作业 MyBatis SQL/故障注入/jvm 日志 / 线上 system 时钟 logging。
     */
    private static final List<String> BASE_LOGS = List.of(
            "[Job] MetricCollectJob start",
            "[Job] MetricCollectJob end, cost=5ms, submitted=1",
            "<==    Updates: 1",
            "[FAULT-INJECT] 业务异常: SPIKE-CASCADE-DISK-FULL volume reached 99 percent, traceId=ddd291d8-4604-478b-bde8-7f52c599e362, amount=264",
            "[FAULT-INJECT] error-log fired: pattern=SPIKE-CASCADE-DISK-FULL volume reached 99 percent, count=1",
            "[Job] AlertDetectJob start",
            "[Job] AlertDetectJob end, cost=10ms, triggered=0",
            "[Alert] 新建告警 id=1039, rule=3, target=99, value=50.0, silenced=false, suppressed=false",
            "==>  Preparing: UPDATE log_template SET last_seen=?, total_count=?, sample_log=?, variables=? WHERE id=?",
            "<==      Total: 1",
            "[FAULT-INJECT] error-log fired: pattern=ORDER-VALIDATION-FAILED: inventory service returned null sku, count=1",
            "[FAULT-INJECT] error-log fired: pattern=DB-CONNECTION-FAILED: could not get connection from pool, count=1",
            "==>  Preparing: INSERT INTO metric_data ( target_id, metric_key, metric_value, collect_time ) VALUES ( ?, ?, ?, ? )",
            "==> Parameters: 2(Long), jvm.heap.usage(String), 2.263617874765334(BigDecimal), 2026-09-30T14:03:50.039962900(LocalDateTime)",
            "==> Parameters: 1(Long)",
            "[LogAnomaly] created type=new_template template=398 title=发现新日志模板：aiops-platform",
            "[Alert] 新建告警 id=551, rule=6, target=99, value=50.0, silenced=true, suppressed=false",
            "==> Parameters: 2026-09-30T14:03:39.594(LocalDateTime), 534(Long), <==      Total: 0(String), [](String), 21(Long)",
            "[LogAnomaly] created type=rare_template template=400 title=稀有模板（仅 1 次）",
            "[FAULT-INJECT] 业务异常: CACHE-MISS-STORM: redis timeout while fetching user session, traceId=05841166-4655-4639-9e83-38d2de67fc8d, amount=587",
            "[FAULT-INJECT] 业务异常: BREAKER-OPEN-M4-FINAL, traceId=2b2e9be8-e60a-41fd-93c3-ee6ca847f4ab, amount=333",
            "[FAULT-INJECT] 业务异常: DB-CONNECTION-FAILED: could not get connection from pool, traceId=0ea15649-fcfe-4d54-a99f-5dafc3a61871, amount=981",
            "[FAULT-INJECT] 业务异常: PAYMENT-GATEWAY-5XX: upstream returned 503 service unavailable, traceId=8526d4fd-3479-43ea-8bbc-c1452bff5953, amount=288",
            "[FAULT-INJECT] 业务异常: ORDER-VALIDATION-FAILED: inventory service returned null sku, traceId=ba8aa243-69e6-480b-a3f6-638577a67b13, amount=103"
    );

    /**
     * 返回特定 depth & simTh 的 DrainParser 答案统计。
     * 重复输入 repeatTimes 次 = tree 在训练中逐步收敛 —— 各异 simTh 的 differences become 在 second ordering llewoorc led人工操作。
     */
    private SweepResult runSweep(int depth, double simTh, int repeatTimes) {
        DrainConfig cfg = new DrainConfig();
        cfg.setDepth(depth);
        cfg.setSimTh(simTh);
        cfg.setMaxChildren(100);
        cfg.setMaxCluster(1000);
        DrainParser parser = new DrainParser(cfg);

        Set<String> templates = new HashSet<>();
        for (int rep = 0; rep < repeatTimes; rep++) {
            for (String raw : BASE_LOGS) {
                DrainParser.LogCluster r = parser.addLogMessage(raw);
                if (r != null && r.getTemplateText() != null) {
                    templates.add(r.getTemplateText());
                }
            }
        }
        int templateCount = templates.size();
        int totalLogs = BASE_LOGS.size() * repeatTimes;
        double compressionRate = 1.0 - ((double) templateCount / totalLogs);
        return new SweepResult(depth, simTh, templateCount, compressionRate, totalLogs);
    }

    @Test
    void sweep9GroupsAndPrint() {
        List<SweepResult> results = new ArrayList<>();
        for (int depth : new int[]{3, 4, 5}) {
            for (double simTh : new double[]{0.4, 0.5, 0.6}) {
                // 同输入跑 5 次 —— tree 在训练迭代中获取集中 advantage.
                results.add(runSweep(depth, simTh, 5));
            }
        }
        System.out.println();
        System.out.println("=== M7-T2-E3 Drain Sweep Results (real data, 5x repeat) ===");
        System.out.println("depth | simTh | templates | compression_rate");
        for (SweepResult r : results) {
            System.out.printf("%6d | %5.1f | %9d | %5.1f%%%n",
                    r.depth, r.simTh, r.templateCount, r.compressionRate * 100);
        }
    }

    private static class SweepResult {
        final int depth;
        final double simTh;
        final int templateCount;
        final double compressionRate;
        final int totalLogs;

        SweepResult(int depth, double simTh, int templateCount, double compressionRate, int totalLogs) {
            this.depth = depth;
            this.simTh = simTh;
            this.templateCount = templateCount;
            this.compressionRate = compressionRate;
            this.totalLogs = totalLogs;
        }
    }
}
