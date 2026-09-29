package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.datasource.log.EsQueryBuilder;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.entity.LogTemplateStat;
import com.aiops.module.log.mapper.LogTemplateMapper;
import com.aiops.module.log.mapper.LogTemplateStatMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志模板查询/状态/趋势/样本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogTemplateService {

    private final LogTemplateMapper logTemplateMapper;
    private final LogTemplateStatMapper logTemplateStatMapper;
    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;
    private final EsLogClient esLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Page<LogTemplate> page(long current, long size, Integer status, String keyword, String service) {
        Page<LogTemplate> p = new Page<>(current, size);
        return logTemplateMapper.selectPage(p, new LambdaQueryWrapper<LogTemplate>()
                .eq(status != null, LogTemplate::getStatus, status)
                .eq(service != null && !service.isBlank(), LogTemplate::getService, service)
                .like(keyword != null && !keyword.isBlank(), LogTemplate::getTemplateText, keyword)
                .orderByDesc(LogTemplate::getLastSeen));
    }

    public void updateStatus(Long id, Integer status) {
        LogTemplate t = new LogTemplate();
        t.setId(id);
        t.setStatus(status);
        logTemplateMapper.updateById(t);
    }

    /** 最近 hours 小时窗口计数（供 BaseChart 用） */
    public Map<String, Object> trend(Long id, Integer hours) {
        int h = hours == null ? 24 : hours;
        LocalDateTime since = LocalDateTime.now().minusHours(h);
        List<LogTemplateStat> list = logTemplateStatMapper.selectList(
                new LambdaQueryWrapper<LogTemplateStat>()
                        .eq(LogTemplateStat::getTemplateId, id)
                        .ge(LogTemplateStat::getStatTime, since)
                        .orderByAsc(LogTemplateStat::getStatTime));
        List<String> times = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (LogTemplateStat s : list) {
            times.add(s.getStatTime().toString().replace('T', ' '));
            counts.add(s.getWindowCount());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("times", times);
        out.put("counts", counts);
        return out;
    }

    /** 取该模板最近样本（按 sample_log 关键字去 ES 查最近 size 条） */
    public Map<String, Object> samples(Long id, Integer size) {
        LogTemplate t = logTemplateMapper.selectById(id);
        if (t == null) {
            throw new BizException("模板不存在");
        }
        EsDatasource ds = esDatasourceMapper.selectById(t.getDatasourceId());
        EsIndexConfig idx = esIndexConfigMapper.selectById(t.getIndexConfigId());
        if (ds == null || idx == null) {
            throw new BizException("ES 数据源 / 索引未配置");
        }
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(6);
        // 用模板前两个 token（去 <*>）作为关键字
        String kw = pickKeyword(t.getTemplateText());
        EsQueryBuilder.SearchParams p = new EsQueryBuilder.SearchParams(
                idx.getIndexPattern(), idx.getTimeField(), idx.getMessageField(),
                idx.getLevelField(), idx.getServiceField(), idx.getTraceIdField(),
                start, end,
                t.getLevel() == null ? null : List.of(t.getLevel()),
                t.getService() == null ? null : List.of(t.getService()),
                kw, null, 1, size == null ? 5 : size, "standard");
        String dsl = EsQueryBuilder.buildSearchDsl(p);
        String resp = esLogClient.postSearch(ds.baseUrl(), idx.getIndexPattern() + "/_search", dsl);
        Map<String, Object> out = new HashMap<>();
        out.put("dsl", dsl);
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode hits = root.path("hits");
            out.put("total", hits.path("total").path("value").asLong(0));
            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> row = new HashMap<>();
                row.put("time", src.path(idx.getTimeField()).asText(null));
                row.put("level", src.path(idx.getLevelField()).asText(null));
                row.put("service", src.path(idx.getServiceField()).asText(null));
                row.put("message", src.path(idx.getMessageField()).asText(null));
                row.put("traceId", src.path(idx.getTraceIdField()).asText(null));
                records.add(row);
            }
            out.put("samples", records);
        } catch (Exception e) {
            throw new BizException("ES 响应解析失败：" + e.getMessage());
        }
        out.put("template", t);
        return out;
    }

    private String pickKeyword(String template) {
        if (template == null) return null;
        for (String tok : template.split("\\s+")) {
            if (!tok.startsWith("<") && tok.length() >= 3) {
                return tok;
            }
        }
        return null;
    }
}
