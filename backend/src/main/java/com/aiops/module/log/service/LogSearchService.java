package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.datasource.log.EsQueryBuilder;
import com.aiops.common.util.TimeZones;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.service.LogSourceResolver;
import com.aiops.module.log.dto.LogSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志检索（多条件 + 高亮 + 直方图）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogSearchService {

    private static final DateTimeFormatter IN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LogSourceResolver logSourceResolver;
    private final EsLogClient esLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** POST /api/log/search：返回{total, records, dsl} */
    public Map<String, Object> search(LogSearchRequest body) {
        LogSourceResolver.LogSource source = resolveSource(body);
        EsQueryBuilder.SearchParams p = parseParams(body, source.indexConfig());
        String dsl = EsQueryBuilder.buildSearchDsl(p);
        String resp = esLogClient.postSearch(source.datasource().baseUrl(), p.indexPattern() + "/_search", dsl);
        Map<String, Object> out = new HashMap<>();
        out.put("dsl", dsl);
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode hits = root.path("hits");
            out.put("total", hits.path("total").path("value").asLong(0));
            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) {
                JsonNode src = hit.path("_source");
                JsonNode hl = hit.path("highlight");
                Map<String, Object> row = new HashMap<>();
                row.put("time", toLocalTime(src.path(p.timeField()).asText(null)));
                row.put("level", src.path(p.levelField()).asText(null));
                row.put("service", src.path(p.serviceField()).asText(null));
                row.put("traceId", src.path(p.traceIdField()).asText(null));
                // 高亮：优先用 highlight.message[0] 替换原文
                String msg = src.path(p.messageField()).asText(null);
                if (hl != null && hl.has(p.messageField()) && hl.path(p.messageField()).isArray()
                        && hl.path(p.messageField()).size() > 0) {
                    msg = hl.path(p.messageField()).get(0).asText(msg);
                }
                row.put("message", msg);
                records.add(row);
            }
            out.put("records", records);
        } catch (Exception e) {
            throw new BizException("ES 响应解析失败：" + e.getMessage());
        }
        return out;
    }

    /** POST /api/log/search/histogram：时间直方图 */
    public Map<String, Object> histogram(LogSearchRequest body) {
        LogSourceResolver.LogSource source = resolveSource(body);
        EsQueryBuilder.SearchParams p = parseParams(body, source.indexConfig());
        String dsl = EsQueryBuilder.buildHistogramDsl(p);
        String resp = esLogClient.postSearch(source.datasource().baseUrl(), p.indexPattern() + "/_search", dsl);
        Map<String, Object> out = new HashMap<>();
        out.put("dsl", dsl);
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode buckets = root.path("aggregations").path("per_time").path("buckets");
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode b : buckets) {
                Map<String, Object> row = new HashMap<>();
                row.put("time", toLocalTime(b.path("key_as_string").asText()));
                row.put("count", b.path("doc_count").asLong());
                list.add(row);
            }
            out.put("buckets", list);
        } catch (Exception e) {
            throw new BizException("直方图解析失败：" + e.getMessage());
        }
        return out;
    }

    /** ES 时间（UTC）→ 平台本地时间串，换算规则见 TimeZones */
    private String toLocalTime(String esTime) {
        return TimeZones.esTimeToLocalString(esTime);
    }

    /** 解析前端参数为 SearchParams */
    private EsQueryBuilder.SearchParams parseParams(LogSearchRequest body, EsIndexConfig idx) {
        if (body.getStartTime() == null || body.getEndTime() == null) {
            throw new BizException("startTime / endTime 必填");
        }
        LocalDateTime start = LocalDateTime.parse(body.getStartTime().replace('T', ' '), IN_FMT);
        LocalDateTime end = LocalDateTime.parse(body.getEndTime().replace('T', ' '), IN_FMT);
        return new EsQueryBuilder.SearchParams(
                idx.getIndexPattern(), idx.getTimeField(), idx.getMessageField(),
                idx.getLevelField(), idx.getServiceField(), idx.getTraceIdField(),
                start, end, body.getLevels(), body.getServices(), body.getKeyword(), body.getTraceId(),
                body.getPage() == null ? 1 : body.getPage(),
                body.getSize() == null ? 20 : body.getSize(),
                body.getAnalyzer() == null ? "standard" : body.getAnalyzer());
    }

    /** 前端未选时由 LogSourceResolver 落到默认数据源，不再静默用 id=1 */
    private LogSourceResolver.LogSource resolveSource(LogSearchRequest body) {
        return logSourceResolver.resolve(body.getDatasourceId(), body.getIndexConfigId());
    }
}
