package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.llm.builder.Nl2QueryContextBuilder;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmCallLog;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmCallLogMapper;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.mapper.LlmProviderMapper;
// LlmPromptTemplateMapper used to fetch user_prompt_tpl for placeholder replacement (Option B).
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M5-9 自然语言 → 结构化查询（nl2query）。
 * <p>
 * 流程（任务书 §5.9 + 现有 M5-6 .. M5-8 复用）：
 * <ol>
 *   <li>从 llm_prompt_template 取 scene_code='nl2query' 的模板</li>
 *   <li>buildContextBuilder 提供 metricList / targetList / timeHint</li>
 *   <li>把 ${metricList}、${targetList}、${question}、${timeHint} 渲到 userTpl 成 finalUserMsg</li>
 *   <li>调用 {@link LlmSchemaRetryService#callWithSchema}（其内部只做 ${inputSummary} 替换，
 *       我们把已渲好的字符串传进去，并不会再发生任何替换——保持现有 service 不动）</li>
 *   <li>schema 校验失败 → 由 LlmSchemaRetryService 内部完成 2 次重试；仍失败抛出 BizException</li>
 *   <li>BizException → 走 §7.6 兜底：返回 queryType=unknown + isLlmFallback=true + 3 个示例</li>
 *   <li>成功 → 按 queryType 派发到 metric_query / alert_query / incident_query / unknown 执行器，转 results</li>
 *   <li>二次调用 chat()（非 schema）做 30 字内中文 explain</li>
 *   <li>落 llm_call_log：成功 1 条 success，失败 1 条 fail（ref_id = MD5(question) 前 8 字节 long）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2QueryService {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_METRIC_ROWS = 100;
    private static final int MAX_ALERT_ROWS = 50;
    private static final int MAX_INCIDENT_ROWS = 50;
    private static final int MAX_EXPLAIN_SAMPLE = 5;

    private static final List<String> FALLBACK_EXAMPLES = List.of(
            "今天上午 cpu 最高的服务是哪个",
            "过去一小时磁盘告警",
            "jvm.heap.usage 在 order-service 的趋势");

    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;
    private final LlmSchemaRetryService schemaRetryService;
    private final Nl2QueryContextBuilder contextBuilder;
    private final LlmPromptTemplateMapper promptTemplateMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final MetricDataMapper metricDataMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* ================== 入口 ================== */

    /**
     * 主入口。question 不可为空。
     * 返回值键序固定：query/results/explain/rawLlm/isLlmFallback/attempts。
     */
    public Map<String, Object> nl2query(String question) {
        if (question == null || question.isBlank()) {
            throw new BizException("question 不能为空");
        }
        long refId = questionRefId(question);
        LlmProvider provider = pickDefaultProvider();
        LlmClient client = llmProviderService.buildClient(provider);
        // 多占位符：让 service 层统一处理 system_prompt + user_prompt 的 ${metricList}/${targetList}/${question}/${timeHint}
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("metricList", contextBuilder.buildMetricList());
        placeholders.put("targetList", contextBuilder.buildTargetList());
        placeholders.put("question", question);
        placeholders.put("timeHint", contextBuilder.buildTimeHint());

        LlmSchemaRetryService.SchemaCheckedResult r;
        try {
            r = schemaRetryService.callWithSchema(client, provider.getModelName(), "nl2query", placeholders);
        } catch (BizException e) {
            log.warn("[nl2query] schema retry fail q={} err={}", abbreviate(question, 80), e.getMessage());
            saveCallLogFail("nl2query", refId, e.getMessage());
            return fallback(e.getMessage());
        } catch (Exception e) {
            log.warn("[nl2query] unexpected fail q={} err={}", abbreviate(question, 80), e.getMessage());
            saveCallLogFail("nl2query", refId, "net:" + e.getMessage());
            return fallback("网络异常: " + e.getMessage());
        }

        // 成功：先落 success 日志
        saveCallLogSuccess("nl2query", refId, r);

        ObjectNode query = normalizeQuery(r.parsed());
        List<Map<String, Object>> results = execute(query);
        String explain = summarize(client, provider.getModelName(), query.path("queryType").asText("unknown"), results);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("results", results);
        data.put("explain", explain);
        data.put("rawLlm", r.rawContent());
        data.put("isLlmFallback", false);
        data.put("attempts", r.attempts());
        if ("unknown".equals(query.path("queryType").asText())) {
            ArrayNode ex = objectMapper.createArrayNode();
            FALLBACK_EXAMPLES.forEach(ex::add);
            query.set("examples", ex);
        }
        return data;
    }

    /* ================== 兜底 ================== */

    /** M5-7 兜底：无任何 LLM 结论时返回结构，results=[] */
    private Map<String, Object> fallback(String reason) {
        ObjectNode q = objectMapper.createObjectNode();
        q.put("queryType", "unknown");
        q.putArray("targetIds");
        q.putArray("metricKeys");
        ObjectNode tr = q.putObject("timeRange");
        LocalDateTime now = LocalDateTime.now();
        tr.put("start", now.minusHours(1).format(F));
        tr.put("end", now.format(F));
        q.put("isLlmFallback", true);
        q.put("reason", reason == null ? "未知" : reason);
        ArrayNode ex = q.putArray("examples");
        FALLBACK_EXAMPLES.forEach(ex::add);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", q);
        data.put("results", List.of());
        data.put("explain", "未识别该问题，请尝试示例问题");
        data.put("rawLlm", "");
        data.put("isLlmFallback", true);
        data.put("attempts", 0);
        return data;
    }

    /* ================== prompt 渲染（占位符替换：${metricList}/${targetList}/${question}/${timeHint}） ================== */

    private String renderUserPrompt(String question) {
        LlmPromptTemplate tpl = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<LlmPromptTemplate>()
                        .eq(LlmPromptTemplate::getSceneCode, "nl2query")
                        .eq(LlmPromptTemplate::getEnabled, 1)
                        .last("LIMIT 1"));
        if (tpl == null) {
            throw new BizException("prompt 模板不存在：scene=nl2query");
        }
        String tplText = tpl.getUserPromptTpl() == null ? "" : tpl.getUserPromptTpl();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("metricList", contextBuilder.buildMetricList());
        placeholders.put("targetList", contextBuilder.buildTargetList());
        placeholders.put("question", question);
        placeholders.put("timeHint", contextBuilder.buildTimeHint());

        String out = tplText;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /* ================== 解析：把 LLM JSON 清理为可执行 query 对象 ================== */

    ObjectNode normalizeQuery(JsonNode parsed) {
        ObjectNode q = objectMapper.createObjectNode();
        String queryType = parsed.path("queryType").asText("unknown");
        if (!List.of("metric_query", "alert_query", "incident_query", "unknown").contains(queryType)) {
            queryType = "unknown";
        }
        q.put("queryType", queryType);

        // targetIds：允许名字或 id
        List<MonitorTarget> targets = contextBuilder.listTargets();
        Map<String, Long> nameToId = new HashMap<>();
        Map<String, Long> logServiceToId = new HashMap<>();
        for (MonitorTarget t : targets) {
            if (t.getName() != null) nameToId.put(t.getName(), t.getId());
            if (t.getLogServiceName() != null) logServiceToId.put(t.getLogServiceName(), t.getId());
        }
        ArrayNode ids = q.putArray("targetIds");
        JsonNode targetNode = parsed.path("targetIds");
        if (targetNode.isArray()) {
            for (JsonNode n : targetNode) {
                Long id = resolveTargetId(n, nameToId, logServiceToId);
                if (id != null) ids.add(id);
            }
        }

        ArrayNode mk = q.putArray("metricKeys");
        JsonNode mkNode = parsed.path("metricKeys");
        if (mkNode.isArray()) {
            for (JsonNode n : mkNode) {
                if (n.isTextual()) mk.add(n.asText());
            }
        }

        ObjectNode tr = q.putObject("timeRange");
        JsonNode trNode = parsed.path("timeRange");
        LocalDateTime now = LocalDateTime.now();
        String start = trNode.path("start").asText(now.minusHours(1).format(F));
        String end = trNode.path("end").asText(now.format(F));
        tr.put("start", start);
        tr.put("end", end);
        return q;
    }

    private static Long resolveTargetId(JsonNode n, Map<String, Long> nameToId, Map<String, Long> logServiceToId) {
        if (n == null) return null;
        if (n.isIntegralNumber()) return n.asLong();
        if (!n.isTextual()) return null;
        String s = n.asText().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignore) { /* fall through */ }
        Long id = nameToId.get(s);
        if (id != null) return id;
        return logServiceToId.get(s);
    }

    /* ================== 执行器 ================== */

    List<Map<String, Object>> execute(ObjectNode query) {
        String queryType = query.path("queryType").asText("unknown");
        return switch (queryType) {
            case "metric_query" -> execMetricQuery(query);
            case "alert_query" -> execAlertQuery(query);
            case "incident_query" -> execIncidentQuery(query);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> execMetricQuery(ObjectNode query) {
        List<Long> targetIds = new ArrayList<>();
        query.path("targetIds").forEach(n -> { if (n.isIntegralNumber()) targetIds.add(n.asLong()); });
        List<String> metricKeys = new ArrayList<>();
        query.path("metricKeys").forEach(n -> { if (n.isTextual()) metricKeys.add(n.asText()); });
        LocalDateTime start = parseTime(query.path("timeRange").path("start").asText(null));
        LocalDateTime end = parseTime(query.path("timeRange").path("end").asText(null));

        LambdaQueryWrapper<MetricData> w = new LambdaQueryWrapper<>();
        if (!targetIds.isEmpty()) w.in(MetricData::getTargetId, targetIds);
        if (!metricKeys.isEmpty()) w.in(MetricData::getMetricKey, metricKeys);
        if (start != null) w.ge(MetricData::getCollectTime, start);
        if (end != null) w.le(MetricData::getCollectTime, end);
        w.orderByDesc(MetricData::getCollectTime).last("LIMIT 1000");
        List<MetricData> rows = metricDataMapper.selectList(w);

        // group by (targetId, metricKey)
        Map<String, List<BigDecimal>> grouped = new LinkedHashMap<>();
        for (MetricData m : rows) {
            String key = m.getTargetId() + "|" + m.getMetricKey();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getMetricValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, List<BigDecimal>> e : grouped.entrySet()) {
            if (count++ >= MAX_METRIC_ROWS) break;
            String[] parts = e.getKey().split("\\|", 2);
            List<BigDecimal> vs = e.getValue();
            BigDecimal min = null, max = null, sum = BigDecimal.ZERO;
            for (BigDecimal v : vs) {
                if (v == null) continue;
                if (min == null || v.compareTo(min) < 0) min = v;
                if (max == null || v.compareTo(max) > 0) max = v;
                sum = sum.add(v);
            }
            BigDecimal avg = vs.isEmpty() ? BigDecimal.ZERO
                    : sum.divide(BigDecimal.valueOf(vs.size()), 4, java.math.RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("targetId", Long.parseLong(parts[0]));
            row.put("metricKey", parts.length > 1 ? parts[1] : "");
            row.put("avg", avg);
            row.put("min", min);
            row.put("max", max);
            row.put("count", vs.size());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> execAlertQuery(ObjectNode query) {
        List<Long> targetIds = new ArrayList<>();
        query.path("targetIds").forEach(n -> { if (n.isIntegralNumber()) targetIds.add(n.asLong()); });
        LocalDateTime start = parseTime(query.path("timeRange").path("start").asText(null));
        LocalDateTime end = parseTime(query.path("timeRange").path("end").asText(null));

        LambdaQueryWrapper<AlertRecord> w = new LambdaQueryWrapper<>();
        w.ne(AlertRecord::getStatus, "false_positive");
        if (!targetIds.isEmpty()) w.in(AlertRecord::getTargetId, targetIds);
        if (start != null) w.ge(AlertRecord::getLastTriggerTime, start);
        if (end != null) w.le(AlertRecord::getLastTriggerTime, end);
        w.orderByDesc(AlertRecord::getLastTriggerTime).last("LIMIT " + MAX_ALERT_ROWS);
        List<AlertRecord> rows = alertRecordMapper.selectList(w);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertRecord a : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("targetId", a.getTargetId());
            row.put("metricKey", a.getMetricKey());
            row.put("level", a.getLevel());
            row.put("title", a.getTitle());
            row.put("status", a.getStatus());
            row.put("triggerValue", a.getTriggerValue());
            row.put("lastTriggerTime", a.getLastTriggerTime() == null ? null : a.getLastTriggerTime().format(F));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> execIncidentQuery(ObjectNode query) {
        LambdaQueryWrapper<AlertIncident> w = new LambdaQueryWrapper<>();
        w.orderByDesc(AlertIncident::getCreateTime).last("LIMIT " + MAX_INCIDENT_ROWS);
        List<AlertIncident> rows = alertIncidentMapper.selectList(w);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertIncident i : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.getId());
            row.put("incidentNo", i.getIncidentNo());
            row.put("title", i.getTitle());
            row.put("level", i.getLevel());
            row.put("status", i.getStatus());
            row.put("alertCount", i.getAlertCount());
            row.put("startTime", i.getStartTime() == null ? null : i.getStartTime().format(F));
            out.add(row);
        }
        return out;
    }

    /* ================== explain（第二次 LLM 调用，非 schema） ================== */

    private String summarize(LlmClient client, String model, String queryType, List<Map<String, Object>> results) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("queryType=").append(queryType).append("\n");
            sb.append("结果数=").append(results.size()).append("\n");
            int i = 0;
            for (Map<String, Object> row : results) {
                if (i++ >= MAX_EXPLAIN_SAMPLE) break;
                sb.append("- ").append(objectMapper.writeValueAsString(row)).append("\n");
            }
            List<LlmClient.LlmMessage> msgs = List.of(
                    new LlmClient.LlmMessage("system", "你是运维助手,用30字内中文总结查询结果"),
                    new LlmClient.LlmMessage("user", sb.toString()));
            LlmClient.LlmRequest req = new LlmClient.LlmRequest(model, msgs, 0.3, 128, false);
            LlmClient.LlmResponse resp = client.chat(req);
            String content = resp == null ? null : resp.content();
            if (content == null || content.isBlank()) return "见 query 字段";
            return content.trim();
        } catch (Exception e) {
            log.warn("[nl2query] explain fail: {}", e.getMessage());
            return "见 query 字段";
        }
    }

    /* ================== llm_call_log ================== */

    private void saveCallLogSuccess(String scene, long refId, LlmSchemaRetryService.SchemaCheckedResult r) {
        try {
            LlmCallLog l = new LlmCallLog();
            l.setProviderId(1L);
            l.setSceneCode(scene);
            l.setRefId(refId);
            l.setPromptTokens(r.totalPromptTokens());
            l.setCompletionTokens(r.totalCompletionTokens());
            l.setTotalTokens(r.totalTokens());
            l.setLatencyMs(r.totalLatencyMs());
            l.setStatus("success");
            l.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(l);
        } catch (Exception ex) {
            log.warn("[nl2query] save success log fail: {}", ex.getMessage());
        }
    }

    private void saveCallLogFail(String scene, long refId, String errMsg) {
        try {
            LlmCallLog l = new LlmCallLog();
            l.setProviderId(1L);
            l.setSceneCode(scene);
            l.setRefId(refId);
            l.setStatus("fail");
            l.setErrorMsg(errMsg == null ? "" : (errMsg.length() > 500 ? errMsg.substring(0, 500) : errMsg));
            l.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(l);
        } catch (Exception ex) {
            log.warn("[nl2query] save fail log fail: {}", ex.getMessage());
        }
    }

    /* ================== 工具 ================== */

    /** refId：MD5(question) 前 8 字节转 long（保持 Long 范围可查） */
    static long questionRefId(String q) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(q.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (digest[i] & 0xffL);
            }
            return v & 0x7fffffffffffffffL;
        } catch (Exception e) {
            return Math.abs(q.hashCode());
        }
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim(), F);
        } catch (Exception e) {
            return null;
        }
    }

    private LlmProvider pickDefaultProvider() {
        LlmProvider p = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        return p;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** 给单测直接调用以校验解析路径。包内可见。 */
    ObjectNode normalizeForTest(JsonNode parsed) {
        return normalizeQuery(parsed);
    }

    /** 中文/Locale 兜底，无 operations。 */
    private static String fmt(Locale locale, String template, Object... args) {
        return String.format(locale, template, args);
    }
}
