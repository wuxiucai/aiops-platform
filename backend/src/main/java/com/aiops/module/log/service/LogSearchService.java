package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.datasource.log.EsQueryBuilder;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
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

    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;
    private final EsLogClient esLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** POST /api/log/search：参数 = 检索请求体 Map。返回{total, records, dsl} */
    public Map<String, Object> search(Map<String, Object> body) {
        EsQueryBuilder.SearchParams p = parseParams(body);
        String dsl = EsQueryBuilder.buildSearchDsl(p);
        EsDatasource ds = esDatasourceMapper.selectById(pDatasourceId(body));
        if (ds == null) {
            throw new BizException("ES 数据源未配置");
        }
        String resp = esLogClient.postSearch(ds.baseUrl(), p.indexPattern() + "/_search", dsl);
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
    public Map<String, Object> histogram(Map<String, Object> body) {
        EsQueryBuilder.SearchParams p = parseParams(body);
        String dsl = EsQueryBuilder.buildHistogramDsl(p);
        EsDatasource ds = esDatasourceMapper.selectById(pDatasourceId(body));
        if (ds == null) {
            throw new BizException("ES 数据源未配置");
        }
        String resp = esLogClient.postSearch(ds.baseUrl(), p.indexPattern() + "/_search", dsl);
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

    /**
     * ES 的 @timestamp / date_histogram key_as_string 都是 UTC（形如 2026-09-30T03:01:46.996Z），
     * 直接透传给前端会显示成 UTC 时间，与平台其余地方的 +08:00 本地时间不一致 → 统一转 +8。
     */
    private String toLocalTime(String esTime) {
        if (esTime == null || esTime.isBlank()) return null;
        try {
            String t = esTime;
            if (t.endsWith("Z")) t = t.substring(0, t.length() - 1);
            LocalDateTime utc = LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return utc.plusHours(8).format(IN_FMT);
        } catch (Exception e) {
            return esTime;
        }
    }

    /** 解析前端参数为 SearchParams */
    @SuppressWarnings("unchecked")
    private EsQueryBuilder.SearchParams parseParams(Map<String, Object> body) {
        Long dsId = pDatasourceId(body);
        Long idxId = body.get("indexConfigId") == null ? 1L
                : ((Number) body.get("indexConfigId")).longValue();
        EsIndexConfig idx = esIndexConfigMapper.selectById(idxId);
        if (idx == null) {
            throw new BizException("索引配置不存在");
        }
        String startStr = (String) body.get("startTime");
        String endStr = (String) body.get("endTime");
        if (startStr == null || endStr == null) {
            throw new BizException("startTime / endTime 必填");
        }
        LocalDateTime start = LocalDateTime.parse(startStr.replace('T', ' '), IN_FMT);
        LocalDateTime end = LocalDateTime.parse(endStr.replace('T', ' '), IN_FMT);

        List<String> levels = (List<String>) body.get("levels");
        List<String> services = (List<String>) body.get("services");
        String keyword = (String) body.get("keyword");
        String traceId = (String) body.get("traceId");
        int page = body.get("page") == null ? 1 : ((Number) body.get("page")).intValue();
        int size = body.get("size") == null ? 20 : ((Number) body.get("size")).intValue();
        String analyzer = body.get("analyzer") == null ? "standard" : (String) body.get("analyzer");

        return new EsQueryBuilder.SearchParams(
                idx.getIndexPattern(), idx.getTimeField(), idx.getMessageField(),
                idx.getLevelField(), idx.getServiceField(), idx.getTraceIdField(),
                start, end, levels, services, keyword, traceId, page, size, analyzer);
    }

    private Long pDatasourceId(Map<String, Object> body) {
        Object v = body.get("datasourceId");
        if (v == null) {
            return 1L;
        }
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
    }
}
