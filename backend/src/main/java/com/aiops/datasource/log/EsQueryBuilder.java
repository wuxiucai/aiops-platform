package com.aiops.datasource.log;

import com.aiops.common.util.TimeZones;
import com.aiops.common.BizException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ES Query DSL 构建器：产出可打印可读的 JSON（不压缩），因为该 JSON 要写进论文。
 */
public final class EsQueryBuilder {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EsQueryBuilder() {
    }

    /**
     * term/terms 精确值匹配时落到 logstash 默认 mapping 的 .keyword 子字段。
     * （远程集群未装 IK 分词器，text 字段 terms 查询永远命中 0 —— M3 实测踩过的坑）
     */
    private static String keywordOf(String field) {
        return field.endsWith(".keyword") ? field : field + ".keyword";
    }

    /** 检索请求参数 */
    public record SearchParams(
            String indexPattern,
            String timeField,
            String messageField,
            String levelField,
            String serviceField,
            String traceIdField,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<String> levels,
            List<String> services,
            String keyword,
            String traceId,
            int page,
            int size,
            String analyzer) {
    }

    /** 构建日志检索 DSL（多条件 + 高亮 + 排序 + 分页），格式化为缩进 JSON */
    public static String buildSearchDsl(SearchParams p) {
        if (p.startTime() == null || p.endTime() == null) {
            throw new BizException("时间范围必填");
        }
        if (p.startTime().isAfter(p.endTime())) {
            throw new BizException("开始时间不能晚于结束时间");
        }
        if (java.time.Duration.between(p.startTime(), p.endTime()).toDays() > 7) {
            throw new BizException("时间范围不能超过 7 天");
        }

        List<String> filter = new ArrayList<>();
        // 时间范围：入参是平台本地时间；ES range 无 time_zone 参数时按 UTC 解释 → 换算 UTC
        String startUtc = TimeZones.toUtc(p.startTime()).format(FMT);
        String endUtc = TimeZones.toUtc(p.endTime()).format(FMT);
        filter.add(String.format(
                "{\"range\":{\"%s\":{\"gte\":\"%s\",\"lte\":\"%s\",\"format\":\"yyyy-MM-dd HH:mm:ss||epoch_millis\"}}}",
                p.timeField(), startUtc, endUtc));
        // 级别（logstash 默认 mapping：text + .keyword；terms 精确值必须打 .keyword 子字段）
        if (p.levels() != null && !p.levels().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"");
            terms.append(keywordOf(p.levelField())).append("\":[");
            for (int i = 0; i < p.levels().size(); i++) {
                if (i > 0) terms.append(',');
                terms.append('"').append(escape(p.levels().get(i))).append('"');
            }
            terms.append("]}}");
            filter.add(terms.toString());
        }
        // 服务（同上，打 .keyword）
        if (p.services() != null && !p.services().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"");
            terms.append(keywordOf(p.serviceField())).append("\":[");
            for (int i = 0; i < p.services().size(); i++) {
                if (i > 0) terms.append(',');
                terms.append('"').append(escape(p.services().get(i))).append('"');
            }
            terms.append("]}}");
            filter.add(terms.toString());
        }
        // traceId（term 也需 .keyword）
        if (p.traceId() != null && !p.traceId().isBlank()) {
            filter.add(String.format("{\"term\":{\"%s\":\"%s\"}}", keywordOf(p.traceIdField()), escape(p.traceId())));
        }

        // 关键字（must）
        String must = "\"must\": []";
        if (p.keyword() != null && !p.keyword().isBlank()) {
            // 关键修复：analyzer 必须走白名单（standard / ik_max_word / ik_smart / english / keyword），
            // 之前直接 String.format 拼接用户传入的 analyzer，可被注入任意 JSON 子句。
            String analyzer = sanitizeAnalyzer(p.analyzer());
            must = String.format("{\"match\":{\"%s\":{\"query\":\"%s\",\"analyzer\":\"%s\"}}}",
                    p.messageField(), escape(p.keyword()), analyzer);
            must = "\"must\": [" + must + "]";
        }

        int from = Math.max(0, (p.page() - 1) * p.size());
        if (from + p.size() > 10000) {
            throw new BizException("翻页过深，from+size 不得超过 10000");
        }

        return """
                {
                  "query": {
                    "bool": {
                      %s,
                      "filter": [
                %s
                      ]
                    }
                  },
                  "highlight": {
                    "pre_tags": ["<em>"],
                    "post_tags": ["</em>"],
                    "fields": {"%s": {}}
                  },
                  "sort": [{"%s": {"order": "desc"}}],
                  "from": %d,
                  "size": %d,
                  "_source": ["%s", "%s", "%s", "%s", "%s"]
                }""".formatted(
                must,
                String.join(",\n", filter.stream().map(s -> "        " + s).toList()),
                p.messageField(),
                p.timeField(),
                from, p.size(),
                p.timeField(), p.levelField(), p.serviceField(), p.messageField(), p.traceIdField());
    }

    /** 直方图聚合 DSL：size=0 + date_histogram（fixed_interval 自动选择） */
    public static String buildHistogramDsl(SearchParams p) {
        List<String> filter = new ArrayList<>();
        filter.add(String.format(
                "{\"range\":{\"%s\":{\"gte\":\"%s\",\"lte\":\"%s\",\"format\":\"yyyy-MM-dd HH:mm:ss||epoch_millis\"}}}",
                p.timeField(), TimeZones.toUtc(p.startTime()).format(FMT), TimeZones.toUtc(p.endTime()).format(FMT)));
        if (p.levels() != null && !p.levels().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"").append(keywordOf(p.levelField())).append("\":[");
            for (int i = 0; i < p.levels().size(); i++) {
                if (i > 0) terms.append(',');
                terms.append('"').append(escape(p.levels().get(i))).append('"');
            }
            terms.append("]}}");
            filter.add(terms.toString());
        }
        String interval = pickInterval(p.startTime(), p.endTime());
        return """
                {
                  "size": 0,
                  "query": {"bool": {"filter": [%s]}},
                  "aggs": {
                    "per_time": {
                      "date_histogram": {
                        "field": "%s",
                        "fixed_interval": "%s",
                        "format": "yyyy-MM-dd HH:mm:ss",
                        "min_doc_count": 0
                      }
                    }
                  }
                }""".formatted(String.join(",", filter), p.timeField(), interval);
    }

    /** fixed_interval 自动选择：≤1h→1m；≤6h→5m；≤24h→15m；>24h→1h */
    public static String pickInterval(LocalDateTime start, LocalDateTime end) {
        long minutes = java.time.Duration.between(start, end).toMinutes();
        if (minutes <= 60) return "1m";
        if (minutes <= 360) return "5m";
        if (minutes <= 1440) return "15m";
        return "1h";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * analyzer 白名单：只允许 ES 内置合法 analyzer。
     * null/blank/未知值 → 默认 "standard"，且不再原样拼接 JSON 片段。
     * 绕过方式只剩"换一个合法 analyzer"——不影响查询结构。
     */
    private static String sanitizeAnalyzer(String analyzer) {
        if (analyzer == null) return "standard";
        return switch (analyzer) {
            case "standard", "ik_max_word", "ik_smart", "english", "keyword", "whitespace", "simple" -> analyzer;
            default -> {
                // 未知 analyzer：默认 standard，避免注入和 400 错误
                yield "standard";
            }
        };
    }
}
