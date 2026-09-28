package com.aiops.datasource.log;

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
        // 时间范围
        filter.add(String.format(
                "{\"range\":{\"%s\":{\"gte\":\"%s\",\"lte\":\"%s\",\"format\":\"yyyy-MM-dd HH:mm:ss||epoch_millis\"}}}",
                p.timeField(), p.startTime().format(FMT), p.endTime().format(FMT)));
        // 级别
        if (p.levels() != null && !p.levels().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"");
            terms.append(p.levelField()).append("\":[");
            for (int i = 0; i < p.levels().size(); i++) {
                if (i > 0) terms.append(',');
                terms.append('"').append(escape(p.levels().get(i))).append('"');
            }
            terms.append("]}}");
            filter.add(terms.toString());
        }
        // 服务
        if (p.services() != null && !p.services().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"");
            terms.append(p.serviceField()).append("\":[");
            for (int i = 0; i < p.services().size(); i++) {
                if (i > 0) terms.append(',');
                terms.append('"').append(escape(p.services().get(i))).append('"');
            }
            terms.append("]}}");
            filter.add(terms.toString());
        }
        // traceId
        if (p.traceId() != null && !p.traceId().isBlank()) {
            filter.add(String.format("{\"term\":{\"%s\":\"%s\"}}", p.traceIdField(), escape(p.traceId())));
        }

        // 关键字（must）
        String must = "\"must\": []";
        if (p.keyword() != null && !p.keyword().isBlank()) {
            must = String.format("{\"match\":{\"%s\":{\"query\":\"%s\",\"analyzer\":\"%s\"}}}",
                    p.messageField(), escape(p.keyword()), p.analyzer());
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
                p.timeField(), p.startTime().format(FMT), p.endTime().format(FMT)));
        if (p.levels() != null && !p.levels().isEmpty()) {
            StringBuilder terms = new StringBuilder("{\"terms\":{\"").append(p.levelField()).append("\":[");
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
}
