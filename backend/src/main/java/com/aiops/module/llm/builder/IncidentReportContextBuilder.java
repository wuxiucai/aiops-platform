package com.aiops.module.llm.builder;

import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.entity.IncidentTimeline;
import com.aiops.module.incident.mapper.IncidentTimelineMapper;
import com.aiops.module.llm.util.LogTemplateSummaryBuilder;
import com.aiops.module.llm.util.MetricsSummarizer;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.entity.LogTemplateStat;
import com.aiops.module.log.mapper.LogTemplateMapper;
import com.aiops.module.log.mapper.LogTemplateStatMapper;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M5-10 故障报告上下文组装器。
 * <p>
 * 把 incident + alerts + timeline + log 模板 + metric 摘要 + KB 案例
 * 压缩成一个 ≤ 4000 中文字符的 user prompt 上下文。
 * <p>
 * 截断策略（超限之后）：
 *   1. 先砍 alert 列表（保留最近 5 条）
 *   2. 再砍 timeline（保留最近 10 条）
 *   3. 再砍 log 模板摘要（仅保留首段）
 *   4. 最后无边硬截（保底）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentReportContextBuilder {

    /** 总 prompt 上下文硬上限（中文字符 ≈ token） */
    public static final int MAX_CONTEXT_CHARS = 4000;

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertRecordMapper alertRecordMapper;
    private final IncidentTimelineMapper incidentTimelineMapper;
    private final MetricDataMapper metricDataMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final LogTemplateMapper logTemplateMapper;
    private final LogTemplateStatMapper logTemplateStatMapper;

    /** 返回结构化组装结果（每个段都是独立 String，调用方可放进 ${} 占位符） */
    public ReportContext build(AlertIncident inc) {
        String incidentInfo = buildIncidentInfo(inc);
        String alertList = buildAlertList(inc.getId(), 10);
        String timeline = buildTimeline(inc.getId(), 20);
        String logSummary = buildLogSummary(inc);
        String metricsSummary = buildMetricsSummary(inc);
        String similarCases = buildSimilarCases();

        ReportContext ctx = new ReportContext(
                incidentInfo, alertList, timeline, logSummary, metricsSummary, similarCases);

        // 长度控制：总量 ≤ MAX_CONTEXT_CHARS，超限时按段截
        int total = ctx.totalLength();
        if (total > MAX_CONTEXT_CHARS) {
            log.info("[ReportCtx] 超过 {} 字符 ({}), 启动截断", MAX_CONTEXT_CHARS, total);
            // 1) 砍 alert 列表
            ctx = new ReportContext(ctx.incidentInfo(),
                    buildAlertList(inc.getId(), 5), ctx.timeline(),
                    ctx.logSummary(), ctx.metricsSummary(), ctx.similarCases());
        }
        if (ctx.totalLength() > MAX_CONTEXT_CHARS) {
            // 2) 砍 timeline
            ctx = new ReportContext(ctx.incidentInfo(), ctx.alertList(),
                    buildTimeline(inc.getId(), 10),
                    ctx.logSummary(), ctx.metricsSummary(), ctx.similarCases());
        }
        if (ctx.totalLength() > MAX_CONTEXT_CHARS) {
            // 3) 砍 log 摘要：仅保留首段【日志概况】
            String logFirst = firstParagraph(ctx.logSummary());
            ctx = new ReportContext(ctx.incidentInfo(), ctx.alertList(), ctx.timeline(),
                    logFirst, ctx.metricsSummary(), ctx.similarCases());
        }
        if (ctx.totalLength() > MAX_CONTEXT_CHARS) {
            // 4) 最后兜底硬截 metric + log
            String ms = truncate(ctx.metricsSummary(), 800);
            String ls = truncate(ctx.logSummary(), 800);
            ctx = new ReportContext(ctx.incidentInfo(), ctx.alertList(), ctx.timeline(),
                    ls, ms, ctx.similarCases());
        }
        return ctx;
    }

    /* ================== 段构造 ================== */

    private String buildIncidentInfo(AlertIncident inc) {
        return String.format(Locale.ROOT,
                "标题=%s 级别=%s 状态=%s 开始=%s 结束=%s 聚合告警数=%s",
                nz(inc.getTitle()), nz(inc.getLevel()), nz(inc.getStatus()),
                inc.getStartTime() == null ? "-" : inc.getStartTime().format(F),
                inc.getEndTime() == null ? "-" : inc.getEndTime().format(F),
                inc.getAlertCount() == null ? "0" : inc.getAlertCount().toString());
    }

    private String buildAlertList(Long incidentId, int limit) {
        List<AlertRecord> alerts = alertRecordMapper.selectList(
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(AlertRecord::getIncidentId, incidentId)
                        .orderByAsc(AlertRecord::getFirstTriggerTime)
                        .last("LIMIT " + limit));
        if (alerts.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (AlertRecord a : alerts) {
            sb.append(String.format(Locale.ROOT,
                    "[%s] target=%s metric=%s value=%s status=%s\n",
                    a.getFirstTriggerTime() == null ? "-" : a.getFirstTriggerTime().format(F),
                    a.getTargetId() == null ? "-" : a.getTargetId().toString(),
                    nz(a.getMetricKey()),
                    a.getTriggerValue() == null ? "-" : a.getTriggerValue().toPlainString(),
                    nz(a.getStatus())));
        }
        return sb.toString().trim();
    }

    private String buildTimeline(Long incidentId, int limit) {
        List<IncidentTimeline> events = incidentTimelineMapper.selectList(
                new LambdaQueryWrapper<IncidentTimeline>()
                        .eq(IncidentTimeline::getIncidentId, incidentId)
                        .orderByAsc(IncidentTimeline::getEventTime)
                        .last("LIMIT " + limit));
        if (events.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (IncidentTimeline e : events) {
            sb.append(String.format(Locale.ROOT, "[%s] %s: %s\n",
                    e.getEventTime() == null ? "-" : e.getEventTime().format(F),
                    nz(e.getEventType()), nz(e.getDescription())));
        }
        return sb.toString().trim();
    }

    /** 日志概况：基于 incident.primary_target_id 找 monitor_target → log_service_name → 时间窗内 log_template */
    private String buildLogSummary(AlertIncident inc) {
        try {
            if (inc.getPrimaryTargetId() == null) return "(无日志上下文)";
            MonitorTarget target = monitorTargetMapper.selectById(inc.getPrimaryTargetId());
            if (target == null) return "(无日志上下文: target 不存在)";
            String serviceName = target.getLogServiceName();
            if (serviceName == null || serviceName.isBlank()) return "(无日志上下文: target 未绑定 log_service_name)";

            LocalDateTime start = inc.getStartTime() == null
                    ? LocalDateTime.now().minusHours(1) : inc.getStartTime();
            LocalDateTime end = inc.getEndTime() == null
                    ? LocalDateTime.now() : inc.getEndTime();

            List<LogTemplate> templates = logTemplateMapper.selectList(
                    new LambdaQueryWrapper<LogTemplate>()
                            .eq(LogTemplate::getService, serviceName)
                            .ge(LogTemplate::getLastSeen, start)
                            .le(LogTemplate::getLastSeen, end)
                            .orderByDesc(LogTemplate::getTotalCount)
                            .last("LIMIT 30"));
            if (templates.isEmpty()) return "(时间窗内无相关日志模板)";

            // window stat：前一窗口（用于比对）
            List<Long> tIds = templates.stream().map(LogTemplate::getId).collect(Collectors.toList());
            List<LogTemplateStat> stats = tIds.isEmpty() ? List.of()
                    : logTemplateStatMapper.selectList(
                            new LambdaQueryWrapper<LogTemplateStat>()
                                    .in(LogTemplateStat::getTemplateId, tIds)
                                    .ge(LogTemplateStat::getStatTime, start)
                                    .le(LogTemplateStat::getStatTime, end)
                                    .orderByDesc(LogTemplateStat::getStatTime)
                                    .last("LIMIT 200"));

            Map<Long, Long> countByTpl = new LinkedHashMap<>();
            for (LogTemplateStat s : stats) {
                countByTpl.merge(s.getTemplateId(), (long) (s.getWindowCount() == null ? 0 : s.getWindowCount()), Long::sum);
            }

            List<LogTemplateSummaryBuilder.TemplateStat> tplStats = new ArrayList<>();
            long totalCount = 0;
            for (LogTemplate t : templates) {
                long inWindow = countByTpl.getOrDefault(t.getId(), 0L);
                if (inWindow == 0 && t.getLastWindowCount() != null) inWindow = t.getLastWindowCount();
                totalCount += inWindow;
                tplStats.add(new LogTemplateSummaryBuilder.TemplateStat(
                        t.getId(),
                        t.getTemplateText(),
                        t.getLevel(),
                        inWindow,
                        0, inWindow,
                        t.getService()));
            }

            List<LogTemplateSummaryBuilder.NewTemplate> newTpls = new ArrayList<>();
            for (LogTemplate t : templates) {
                if (t.getFirstSeen() != null && !t.getFirstSeen().isBefore(start)
                        && !t.getFirstSeen().isAfter(end)) {
                    newTpls.add(new LogTemplateSummaryBuilder.NewTemplate(
                            t.getId(), t.getTemplateText(), t.getLevel(),
                            t.getFirstSeen(), countByTpl.getOrDefault(t.getId(), 0L)));
                }
            }

            String levelDist = LogTemplateSummaryBuilder.levelDistributionOf(tplStats);
            return LogTemplateSummaryBuilder.build(start, end, totalCount, templates.size(),
                    levelDist, tplStats, newTpls, List.of());
        } catch (Exception e) {
            log.warn("[ReportCtx] 日志摘要构建失败: {}", e.getMessage());
            return "(日志摘要构建失败: " + e.getMessage() + ")";
        }
    }

    /** 指标摘要：top 3 metric_key 各 1 行 */
    private String buildMetricsSummary(AlertIncident inc) {
        try {
            if (inc.getPrimaryTargetId() == null) return "(无指标上下文)";
            LocalDateTime start = inc.getStartTime() == null
                    ? LocalDateTime.now().minusHours(1) : inc.getStartTime();
            LocalDateTime end = inc.getEndTime() == null
                    ? LocalDateTime.now() : inc.getEndTime();

            // 拉该 target 在时间窗内全部 metric_data，按 metric_key 分组，取点数最多的前 3 个
            List<MetricData> data = metricDataMapper.selectList(
                    new LambdaQueryWrapper<MetricData>()
                            .eq(MetricData::getTargetId, inc.getPrimaryTargetId())
                            .ge(MetricData::getCollectTime, start)
                            .le(MetricData::getCollectTime, end)
                            .last("LIMIT 2000"));
            if (data.isEmpty()) return "(时间窗内无指标数据)";

            Map<String, List<MetricsSummarizer.Point>> grouped = new LinkedHashMap<>();
            for (MetricData m : data) {
                if (m.getMetricKey() == null || m.getMetricValue() == null
                        || m.getCollectTime() == null) continue;
                long ts = m.getCollectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                grouped.computeIfAbsent(m.getMetricKey(), k -> new ArrayList<>())
                        .add(new MetricsSummarizer.Point(ts, m.getMetricValue().doubleValue()));
            }
            if (grouped.isEmpty()) return "(无指标数据)";

            List<Map.Entry<String, List<MetricsSummarizer.Point>>> ordered = grouped.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .limit(3)
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<MetricsSummarizer.Point>> e : ordered) {
                sb.append(MetricsSummarizer.summarize(e.getKey(), e.getValue())).append("\n---\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[ReportCtx] 指标摘要构建失败: {}", e.getMessage());
            return "(指标摘要构建失败: " + e.getMessage() + ")";
        }
    }

    /** 知识库相似案例：表未建好/未填数据时返回占位符 */
    private String buildSimilarCases() {
        // NOTE: kb_fault_case 表当前无 mapper / 实现，M5-10 暂以占位处理
        return "(知识库暂空)";
    }

    /* ================== 工具 ================== */

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String firstParagraph(String s) {
        if (s == null) return "";
        int idx = s.indexOf("\n【模板统计】");
        if (idx < 0) return s.length() <= 800 ? s : s.substring(0, 800);
        return s.substring(0, idx);
    }

    /** 结构化六段结果 */
    public record ReportContext(
            String incidentInfo,
            String alertList,
            String timeline,
            String logSummary,
            String metricsSummary,
            String similarCases) {

        public int totalLength() {
            return nz(incidentInfo).length() + nz(alertList).length() + nz(timeline).length()
                    + nz(logSummary).length() + nz(metricsSummary).length() + nz(similarCases).length()
                    + 100 /* 模板壳 */;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }
}
