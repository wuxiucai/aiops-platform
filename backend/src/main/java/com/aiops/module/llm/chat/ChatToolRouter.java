package com.aiops.module.llm.chat;

import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.kb.entity.KbFaultCase;
import com.aiops.module.kb.mapper.KbFaultCaseMapper;
import com.aiops.module.log.entity.LogAnomaly;
import com.aiops.module.log.mapper.LogAnomalyMapper;
import com.aiops.module.log.service.Nl2DslService;
import com.aiops.module.llm.service.IncidentReportService;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维助手意图识别 + 工具执行（论文 §5.7 RAG/Tool-augmented LLM 设计）。
 * <p>
 * 不接 LangChain / Function Calling，原因：
 *   1. 答辩可解释性强：每个意图对应一个明确的 SQL 查询，结果可追溯
 *   2. 无幻觉风险：数据来自 DB，LLM 只做自然语言包装
 *   3. 失败可控：路由未命中 → 走原有裸 LLM 兜底（带提示告知用户）
 * <p>
 * 已支持意图：
 *   - 未解决告警计数    "多少/几条 unresolved/未解决 告警/incident"
 *   - 最新告警          "最新/今天 critical/error 告警"
 *   - 指标 TopN         "CPU/内存 最高/Top 服务/机器"
 *   - 单 target 最新指标 "xx 服务最近 CPU/内存"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatToolRouter {

    private final AlertRecordMapper alertRecordMapper;
    private final MetricDataMapper metricDataMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final LogAnomalyMapper logAnomalyMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final KbFaultCaseMapper kbFaultCaseMapper;
    private final Nl2DslService nl2DslService;
    private final IncidentReportService incidentReportService;

    /**
     * 意图识别 + 工具执行。
     *
     * @return null 表示未命中已知意图（上层走兜底）；非 null 即工具结果上下文
     */
    public ToolResult route(String question) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase();
        if (log.isDebugEnabled()) {
            log.debug("[ChatToolRouter] routing question len={}", q.length());
        }

        // ---- 1) 未解决告警计数 ----
        boolean hasUnresolvedKw = q.contains("unresolved") || q.contains("未解决")
                || q.contains("未处理") || q.contains("待处理")
                || (q.contains("active") && !q.contains("inactive"));
        boolean hasCountKw = q.contains("多少") || q.contains("几条") || q.contains("几个")
                || q.contains("count") || q.contains("how many") || q.contains("number of")
                || q.contains("总数") || q.contains("数量");
        boolean hasAlertKw = q.contains("告警") || q.contains("alert")
                || q.contains("incident") || q.contains("事件") || q.contains("警报");
        if (hasUnresolvedKw && hasCountKw && hasAlertKw) {
            return countUnresolved();
        }

        // ---- 2) 最新告警 ----
        boolean hasRecentKw = q.contains("最新") || q.contains("今天") || q.contains("最近")
                || q.contains("latest") || q.contains("recent") || q.contains("today")
                || q.contains("this hour") || q.contains("这一小时") || q.contains("刚刚");
        if (hasRecentKw && hasAlertKw) {
            String level = extractLevel(q);
            return listRecentAlerts(level, 24);
        }

        // ---- 3) 指标 TopN："CPU 最高/内存 Top" ----
        boolean hasTopKw = q.contains("最高") || q.contains("top") || q.contains("排名")
                || q.contains("占用最多") || q.contains("highest") || q.contains("most")
                || q.contains("largest") || q.contains("max");
        boolean hasMetricKw = q.contains("cpu") || q.contains("内存") || q.contains("memory")
                || q.contains("mem") || q.contains("磁盘") || q.contains("disk")
                || q.contains("网络") || q.contains("network") || q.contains("net");
        if (hasTopKw && hasMetricKw) {
            String metricKey = extractMetricKey(q);
            return topMetric(metricKey, 24, 5);
        }

        // ---- 4) 日志智能查询：把自然语言问题转 ES DSL 并执行（复用 Nl2DslService） ----
        boolean hasLogKw = q.contains("日志") || q.contains("log") || q.contains("报错")
                || q.contains("exception") || q.contains("timeout") || q.contains("错误信息");
        boolean hasAnalyzeKw = q.contains("特点") || q.contains("分析") || q.contains("查一下")
                || q.contains("看看") || q.contains("找出") || q.contains("整理")
                || q.contains("统计") || q.contains("分布") || q.contains("特征")
                || q.contains("analyze") || q.contains("recent") || q.contains("list");
        if (hasLogKw && hasAnalyzeKw) {
            return queryLogsByNl(question);
        }

        // ---- 5) 异常摘要：近 24h 系统/服务/日志 有什么异常 ----
        boolean hasAnomalyKw = q.contains("异常") || q.contains("anomal") || q.contains("出问题")
                || q.contains("故障") || q.contains("有问题") || q.contains("不正常");
        boolean hasSummaryKw = q.contains("摘要") || q.contains("汇总") || q.contains("summar")
                || q.contains("哪些") || q.contains("有什么") || q.contains("情况")
                || hasRecentKw;
        if (hasAnomalyKw && hasSummaryKw) {
            return summarizeRecentAnomalies(24);
        }

        // ---- 6) 故障报告 / 根因分析 ----
        boolean hasIncidentKw = q.contains("事件") || q.contains("incident") || q.contains("故障");
        boolean hasRcaKw = q.contains("根因") || q.contains("原因") || q.contains("为什么")
                || q.contains("分析") || q.contains("rca") || q.contains("报告")
                || q.contains("调查") || q.contains("排查");
        if (hasIncidentKw && hasRcaKw) {
            return analyzeLatestIncident();
        }

        // ---- 7) 相似案例搜索（基于 kb_fault_case） ----
        boolean hasSimilarKw = q.contains("类似") || q.contains("相似") || q.contains("similar")
                || q.contains("有没有遇到过") || q.contains("以前") || q.contains("历史")
                || q.contains("类似案例") || q.contains("经验");
        if (hasSimilarKw) {
            return searchSimilarCases(question, 3);
        }

        // ---- 4) 未命中 ----
        return null;
    }

    /* =============== 工具实现 =============== */

    private ToolResult countUnresolved() {
        // alert_record.status: unresolved / acknowledged / resolved
        Long cnt = alertRecordMapper.selectCount(
                new LambdaQueryWrapper<AlertRecord>().eq(AlertRecord::getStatus, "unresolved"));
        Map<String, Object> data = new HashMap<>();
        data.put("unresolvedCount", cnt);
        return new ToolResult("countUnresolvedAlerts", data,
                "当前未解决的告警记录数为 " + cnt + " 条。");
    }

    private ToolResult listRecentAlerts(String level, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        LambdaQueryWrapper<AlertRecord> w = new LambdaQueryWrapper<AlertRecord>()
                .ge(AlertRecord::getLastTriggerTime, since)
                .orderByDesc(AlertRecord::getLastTriggerTime)
                .last("LIMIT 10");
        if (level != null) {
            w.eq(AlertRecord::getLevel, level);
        }
        List<AlertRecord> list = alertRecordMapper.selectList(w);
        Map<String, Object> data = new HashMap<>();
        data.put("level", level == null ? "all" : level);
        data.put("sinceHours", hours);
        data.put("count", list.size());
        data.put("items", list.stream().map(a -> Map.of(
                "title", a.getTitle() == null ? "" : a.getTitle(),
                "level", a.getLevel() == null ? "" : a.getLevel(),
                "status", a.getStatus() == null ? "" : a.getStatus(),
                "metricKey", a.getMetricKey() == null ? "" : a.getMetricKey()
        )).toList());
        String summary = "最近 " + hours + " 小时内命中 " + list.size() + " 条"
                + (level == null ? "" : "级别 " + level + " 的") + "告警。";
        return new ToolResult("listRecentAlerts", data, summary);
    }

    private ToolResult topMetric(String metricKey, int hours, int topN) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        // 按 target 分组取每个 target 最新值，再排序
        List<MetricData> recent = metricDataMapper.selectList(
                new LambdaQueryWrapper<MetricData>()
                        .eq(MetricData::getMetricKey, metricKey)
                        .ge(MetricData::getCollectTime, since)
                        .orderByDesc(MetricData::getCollectTime)
                        .last("LIMIT 2000"));
        if (recent.isEmpty()) {
            return new ToolResult("topMetric", Map.of("metricKey", metricKey, "items", List.of()),
                    "最近 " + hours + " 小时没有 " + metricKey + " 指标数据。");
        }
        // target_id → 最新值
        Map<Long, MetricData> latest = new HashMap<>();
        for (MetricData m : recent) {
            latest.putIfAbsent(m.getTargetId(), m);
        }
        List<Map<String, Object>> items = latest.values().stream()
                .sorted((a, b) -> b.getMetricValue().compareTo(a.getMetricValue()))
                .limit(topN)
                .map(m -> {
                    MonitorTarget t = monitorTargetMapper.selectById(m.getTargetId());
                    Map<String, Object> row = new HashMap<>();
                    row.put("targetId", m.getTargetId());
                    row.put("targetName", t == null ? "unknown" : t.getName());
                    row.put("value", m.getMetricValue().setScale(2, RoundingMode.HALF_UP));
                    row.put("collectTime", m.getCollectTime().toString());
                    return row;
                })
                .toList();
        Map<String, Object> data = new HashMap<>();
        data.put("metricKey", metricKey);
        data.put("hours", hours);
        data.put("items", items);
        StringBuilder sb = new StringBuilder("最近 ").append(hours).append(" 小时 ")
                .append(metricKey).append(" Top").append(topN).append("：");
        for (Map<String, Object> it : items) {
            sb.append(" ").append(it.get("targetName")).append("=").append(it.get("value")).append(";");
        }
        return new ToolResult("topMetric", data, sb.toString());
    }

    /* =============== 4) 日志智能查询（NL → DSL → ES） =============== */

    /**
     * 用户问"近 1 小时 order-service 的错误日志有什么特点"：
     *   把整句交给现有 Nl2DslService 生成 DSL（含 schema 校验 + 安全白名单 + 危险动词拦截），
     *   然后直接执行拿结果。这个过程会调一次额外的 LLM（5-10s），但 answer 字段是
     *   Nl2DslService 已经凝练过的中文摘要，可直接交给 chat LLM 做最终回答。
     */
    private ToolResult queryLogsByNl(String question) {
        try {
            Map<String, Object> gen = nl2DslService.generate(null, question);
            Boolean validated = (Boolean) gen.get("validated");
            if (validated == null || !validated) {
                Object reasons = gen.getOrDefault("errors", List.of("生成 DSL 未通过校验"));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("validated", false);
                data.put("errors", reasons);
                return new ToolResult("queryLogsByNl", data,
                        "尝试把问题转成 ES 查询，但 DSL 校验未通过：" + reasons
                                + "。建议精简时间范围、服务名和级别后重试。");
            }
            Long recordId = ((Number) gen.get("recordId")).longValue();
            Map<String, Object> exec = nl2DslService.execute(null, recordId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", exec.get("total"));
            data.put("serviceCounts", exec.get("serviceCounts"));
            Object hits = exec.get("hits");
            if (hits instanceof List<?> list && list.size() > 5) {
                data.put("hitsTop5", list.subList(0, 5));
            } else {
                data.put("hits", hits);
            }
            String summary = "ES 查询命中 " + exec.get("total") + " 条"
                    + "；服务分布=" + exec.get("serviceCounts")
                    + "；前几条样本=" + data.getOrDefault("hitsTop5", data.get("hits"));
            return new ToolResult("queryLogsByNl", data, summary);
        } catch (Exception e) {
            log.warn("[ChatToolRouter] queryLogsByNl fail: {}", e.getMessage());
            return new ToolResult("queryLogsByNl",
                    Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage()),
                    "日志查询工具失败：" + e.getMessage() + "。可直接告诉用户前往「日志智能分析」手动查询。");
        }
    }

    /* =============== 5) 近 N 小时异常摘要 =============== */

    private ToolResult summarizeRecentAnomalies(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<LogAnomaly> list = logAnomalyMapper.selectList(
                new LambdaQueryWrapper<LogAnomaly>()
                        .ge(LogAnomaly::getLastTime, since)
                        .orderByDesc(LogAnomaly::getLastTime)
                        .last("LIMIT 20"));
        Map<String, Long> byType = new LinkedHashMap<>();
        for (LogAnomaly a : list) {
            byType.merge(a.getAnomalyType() == null ? "unknown" : a.getAnomalyType(),
                    a.getCount() == null ? 1L : a.getCount().longValue(), Long::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hours", hours);
        data.put("totalAnomalies", list.size());
        data.put("byType", byType);
        data.put("items", list.stream().map(a -> Map.of(
                "type", a.getAnomalyType() == null ? "" : a.getAnomalyType(),
                "title", a.getTitle() == null ? "" : a.getTitle(),
                "level", a.getLevel() == null ? "" : a.getLevel(),
                "count", a.getCount() == null ? 0 : a.getCount()
        )).toList());
        String summary = "最近 " + hours + " 小时共记录 " + list.size()
                + " 条日志异常；按类型分布=" + byType
                + "；最新样本标题="
                + (list.isEmpty() ? "(无)" : list.get(0).getTitle());
        return new ToolResult("summarizeRecentAnomalies", data, summary);
    }

    /* =============== 6) 最新事件根因分析 =============== */

    private ToolResult analyzeLatestIncident() {
        AlertIncident latest = alertIncidentMapper.selectOne(
                new LambdaQueryWrapper<AlertIncident>()
                        .orderByDesc(AlertIncident::getStartTime)
                        .last("LIMIT 1"));
        if (latest == null) {
            return new ToolResult("analyzeLatestIncident",
                    Map.of("incidents", 0),
                    "系统中还没有任何 alert_incident 事件，无法进行根因分析。");
        }
        try {
            IncidentReportService.ReportResult r = incidentReportService.generateReport(latest.getId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("incidentId", latest.getId());
            data.put("title", latest.getTitle());
            data.put("status", latest.getStatus());
            data.put("cached", r.cached());
            data.put("latencyMs", r.latencyMs());
            // markdown 可能很长，给上游一个截断版本以节约 token
            String md = r.markdown() == null ? "" : r.markdown();
            data.put("markdown", md.length() > 2000 ? md.substring(0, 2000) + "\n...(截断)" : md);
            String summary = "最新事件 #" + latest.getId() + "「" + latest.getTitle() + "」根因报告（cached=" + r.cached() + "）：\n" + data.get("markdown");
            return new ToolResult("analyzeLatestIncident", data, summary);
        } catch (Exception e) {
            log.warn("[ChatToolRouter] analyzeLatestIncident fail: {}", e.getMessage());
            return new ToolResult("analyzeLatestIncident",
                    Map.of("incidentId", latest.getId(), "error", e.getMessage() == null ? "unknown" : e.getMessage()),
                    "事件 #" + latest.getId() + " 根因分析失败：" + e.getMessage());
        }
    }

    /* =============== 7) 相似案例搜索 =============== */

    /**
     * 简化版相似案例检索：先从 question 提取关键名词（服务名 / 错误关键词），
     * 用 LIKE 在 kb_fault_case.title+symptom 上做倒排命中。Embedding 维度交由后续
     * /api/ai/similar-case 接口处理（那条链路要求 incidentId）。
     */
    private ToolResult searchSimilarCases(String question, int topK) {
        String[] tokens = tokenizeForKb(question);
        if (tokens.length == 0) {
            return new ToolResult("searchSimilarCases", Map.of("items", List.of()),
                    "无法从该问题提取关键名词，跳过相似案例检索。");
        }
        // 每个 token 至少命中一个的 fault case
        List<KbFaultCase> all = kbFaultCaseMapper.selectList(
                new LambdaQueryWrapper<KbFaultCase>()
                        .eq(KbFaultCase::getDeleted, 0)
                        .last("LIMIT 200"));
        List<Map<String, Object>> hits = all.stream()
                .map(c -> {
                    String text = (c.getTitle() == null ? "" : c.getTitle()) + "\n"
                            + (c.getSymptom() == null ? "" : c.getSymptom()) + "\n"
                            + (c.getRootCause() == null ? "" : c.getRootCause());
                    long score = 0;
                    for (String t : tokens) {
                        if (text.toLowerCase().contains(t.toLowerCase())) score++;
                    }
                    return Map.<String, Object>of(
                            "case", c, "score", score);
                })
                .filter(m -> (Long) m.get("score") > 0)
                .sorted((a, b) -> Long.compare((Long) b.get("score"), (Long) a.get("score")))
                .limit(topK)
                .map(m -> {
                    KbFaultCase c = (KbFaultCase) m.get("case");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.getId());
                    row.put("title", c.getTitle());
                    row.put("symptom", c.getSymptom() == null ? "" : c.getSymptom());
                    row.put("rootCause", c.getRootCause() == null ? "" : c.getRootCause());
                    row.put("solution", c.getSolution() == null ? "" : c.getSolution());
                    row.put("score", m.get("score"));
                    return row;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokens", List.of(tokens));
        data.put("count", hits.size());
        data.put("items", hits);
        StringBuilder sb = new StringBuilder("知识库中命中 " + hits.size() + " 条相似案例（关键词 " + String.join(",", tokens) + "）：");
        for (Map<String, Object> h : hits) {
            sb.append("\n- 案例 #").append(h.get("id")).append(" 「").append(h.get("title"))
              .append("」：症状=").append(shortStr((String) h.get("symptom"), 80))
              .append(" 根因=").append(shortStr((String) h.get("rootCause"), 60))
              .append(" 处置=").append(shortStr((String) h.get("solution"), 60));
        }
        return new ToolResult("searchSimilarCases", data, sb.toString());
    }

    /** 从问题中提取关键名词：长英文 token / 服务名 / 错误关键词 */
    private String[] tokenizeForKb(String q) {
        if (q == null) return new String[0];
        java.util.Set<String> keep = new java.util.LinkedHashSet<>();
        // 1. 英文 token（长度>=4，可能含 - 或 .）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9._\\-]{3,}")
                .matcher(q);
        while (m.find()) keep.add(m.group());
        // 2. 部分关键中文名词
        String[] zh = {"cpu", "内存", "磁盘", "网络", "数据库", "超时", "缓存", "熔断", "限流",
                "网关", "告警", "异常", "错误", "失败", "崩溃", "宕机", "延迟", "慢", "卡", "错"};
        for (String z : zh) {
            if (q.contains(z)) keep.add(z);
        }
        return keep.toArray(new String[0]);
    }

    private static String shortStr(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /* =============== 工具辅助 =============== */

    private String extractLevel(String q) {
        if (q.contains("critical") || q.contains("严重") || q.contains("紧急")) return "critical";
        if (q.contains("error") || q.contains("错误")) return "error";
        if (q.contains("warn") || q.contains("警告")) return "warning";
        if (q.contains("info") || q.contains("信息")) return "info";
        return null;
    }

    private String extractMetricKey(String q) {
        // metric_key 在数据库里的实际值（看 metric_definition）
        if (q.contains("cpu")) return "cpu.usage";
        if (q.contains("mem") || q.contains("内存")) return "mem.usage";
        if (q.contains("disk") || q.contains("磁盘")) return "disk.usage";
        if (q.contains("net") || q.contains("网络")) return "net.conn.count";
        return "cpu.usage";
    }

    /**
     * @param toolName 工具标识（前端可选展示）
     * @param data     结构化数据（用于调试/日志）
     * @param context  给 LLM 的自然语言上下文（拼到 system prompt）
     */
    public record ToolResult(String toolName, Map<String, Object> data, String context) {}
}
