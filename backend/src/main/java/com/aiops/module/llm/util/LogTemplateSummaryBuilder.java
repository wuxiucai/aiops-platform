package com.aiops.module.llm.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * M5-4：log 模板统计组装器（对应任务书 v2 §6.3B "④ ★ 日志解读 log_explain"）。
 * <p>
 * 负责把 MySQL 里的 log_template / log_template_stat / log_anomaly 数据
 * 组装成 v2 §6.3B 完全一致的格式串：
 *
 * 【时间范围】{timeStart} ~ {timeEnd}
 * 【日志概况】总量：{logCount} 模板数：{templateCount}
 * 级别分布：{levelDistribution}
 * 【模板统计】{templateStats}
 * 【新增模板】{newTemplates}
 * 【关联告警】{relatedAlerts}
 *
 * 该格式可直接贴论文 / LLM prompt。
 */
public class LogTemplateSummaryBuilder {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单个模板 */
    public record TemplateStat(long templateId, String templateText, String level,
                               long count, long prevCount, long delta,
                               String service) {
    }

    /** 新模板 */
    public record NewTemplate(long templateId, String templateText, String level,
                              LocalDateTime firstSeen, long count) {
    }

    /** 关联告警（log_anomaly 记录） */
    public record RelatedAlert(long anomalyId, String type, String level,
                               String title, LocalDateTime startTime) {
    }

    /**
     * 按 v2 §6.3B 组装完整上下文。
     */
    public static String build(LocalDateTime start, LocalDateTime end,
                               long totalLogCount, long totalTemplateCount,
                               String levelDistribution,
                               List<TemplateStat> templateStats,
                               List<NewTemplate> newTemplates,
                               List<RelatedAlert> relatedAlerts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【时间范围】").append(start.format(F)).append(" ~ ").append(end.format(F)).append("\n");
        sb.append("【日志概况】总量：").append(totalLogCount)
                .append(" 模板数：").append(totalTemplateCount).append("\n");
        if (levelDistribution != null && !levelDistribution.isBlank()) {
            sb.append("级别分布：").append(levelDistribution).append("\n");
        }
        sb.append("【模板统计】");
        if (templateStats == null || templateStats.isEmpty()) {
            sb.append("(无)\n");
        } else {
            sb.append("\n");
            // top 10
            int limit = Math.min(10, templateStats.size());
            for (int i = 0; i < limit; i++) {
                TemplateStat t = templateStats.get(i);
                sb.append(String.format(Locale.ROOT,
                        "  - id=%d [%s] %s (count=%d  上期=%d  变化=%+d, service=%s)\n",
                        t.templateId(),
                        t.level() == null ? "INFO" : t.level(),
                        abbreviate(t.templateText(), 80),
                        t.count(), t.prevCount(), t.delta(),
                        t.service() == null ? "-" : t.service()));
            }
            if (templateStats.size() > limit) {
                sb.append(String.format("  ... 共 %d 个模板，已取 Top%d\n", templateStats.size(), limit));
            }
        }
        sb.append("【新增模板】");
        if (newTemplates == null || newTemplates.isEmpty()) {
            sb.append("(无)\n");
        } else {
            sb.append("\n");
            for (NewTemplate n : newTemplates) {
                sb.append(String.format(Locale.ROOT,
                        "  - id=%d [%s] 首次：%s count=%d 模板：%s\n",
                        n.templateId(),
                        n.level() == null ? "INFO" : n.level(),
                        n.firstSeen() == null ? "-" : n.firstSeen().format(F),
                        n.count(),
                        abbreviate(n.templateText(), 80)));
            }
        }
        sb.append("【关联告警】");
        if (relatedAlerts == null || relatedAlerts.isEmpty()) {
            sb.append("(无)\n");
        } else {
            sb.append("\n");
            for (RelatedAlert a : relatedAlerts) {
                sb.append(String.format(Locale.ROOT,
                        "  - #%d [%s/%s] %s (%s)\n",
                        a.anomalyId(),
                        a.level() == null ? "WARN" : a.level(),
                        a.type() == null ? "-" : a.type(),
                        a.title() == null ? "-" : a.title(),
                        a.startTime() == null ? "-" : a.startTime().format(F)));
            }
        }
        return sb.toString();
    }

    /** 便捷：根据 top-N 模板生成简化<级别分布>段（如 ERROR:5, WARN:2, INFO:1） */
    public static String levelDistributionOf(List<TemplateStat> stats) {
        if (stats == null || stats.isEmpty()) return "";
        return stats.stream()
                .collect(Collectors.groupingBy(
                        s -> s.level() == null ? "INFO" : s.level(),
                        Collectors.summingLong(TemplateStat::count)))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
