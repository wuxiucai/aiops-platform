package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.module.llm.service.LlmSchemaRetryService;
import com.aiops.module.log.entity.NlQueryLog;
import com.aiops.module.log.mapper.NlQueryLogMapper;
import com.aiops.module.log.safety.DslSafetyValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M6-1/2/3 业务编排：Natural Language → ES DSL 生成 + 三重安全校验 + 安全执行 + 历史。
 * <p>
 * 流程：
 * <ol>
 *   <li>{@link #generate(Long, String)}：构造 timeHint / fieldInfo / serviceList 占位符 →
 *       调用 {@link LlmSchemaRetryService#callWithSchema(LlmClient, String, String, Map)}
 *       （scene=nl2es_dsl，模板 v2 已 seed 于 DB）→ {@link DslSafetyValidator#validate}
 *       三重校验 → 落 nl_query_log；</li>
 *   <li>{@link #execute(Long, Long)}：nl_query_log 取出 DSL → 二次安全校验 + service 穷举校验 →
 *       调 {@link EsLogClient#postSearch} 真实命中 → 回写 hitCount / executed / answer；</li>
 *   <li>{@link #history(Long, int)}：按 create_time desc 拿最近 N 条。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2DslService {

    /** 校验失败后再喂错回给 LLM 让其修正的额外重试上限（不含 schemaRetry 内部的两次） */
    private static final int VALIDATION_FEEDBACK_RETRIES = 2;
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;
    private final EsLogClient esLogClient;
    private final NlQueryLogMapper nlQueryLogMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;
    private final LlmSchemaRetryService schemaRetryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* ================== 1) generate ================== */

    /**
     * 生成 DSL（仅校验，不执行）。
     * <p>
     * 成功：validated=1, executed=0，返回 {dsl, validated:true, retryCount, recordId}；
     * 失败：validated=0，返回 {dsl, validated:false, errors, retryCount, recordId}。
     */
    public Map<String, Object> generate(Long userId, String question) {
        if (question == null || question.isBlank()) {
            throw new BizException("question 不能为空");
        }
        long t0 = System.currentTimeMillis();
        EsIndexConfig cfg = loadIndexConfig();
        List<String> serviceList = listKnownServices(cfg);

        LlmProvider provider = pickProvider();
        LlmClient client = llmProviderService.buildClient(provider);

        // 语义层前置拦截：问题本身包含破坏性动词（删除/drop/script/update/reindex/remove/clear）
        // 就不送 LLM —— 直接 validated=false + llmBlocked=true，避免 LLM 绕过 prompt 指令
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String[] DANGER_WORDS = {"删除", "drop", "script 计算", "reindex", "drop index", "清空", "清库",
                "aaa_removed_tmp"};
        // 搭配正则：破坏性或计算性操作动词
        java.util.regex.Pattern DANGER = java.util.regex.Pattern.compile(
                "(删除|清空|删除数据|drop|reindex|更新.*信息|更新.*为|update|script（而不是查询）|script 计算|"
                        + "清空|truncate|remove|delete|bulk|清理|修改.*level|修改.*级别)");
        if (DANGER.matcher(q).find()) {
            String reason = "问题要求破坏性操作（含危险动词），本平台只读";
            log.warn("[nl2es_dsl] 语义层前置拦截： {} -> {}", question, reason);
            long tBlocked = System.currentTimeMillis();
            NlQueryLog blocked = persistLog(userId, question, null, 0, null,
                    0, System.currentTimeMillis() - tBlocked, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsl", null);
            out.put("validated", false);
            out.put("llmBlocked", true);
            out.put("reason", reason);
            out.put("errors", List.of(reason));
            out.put("retryCount", 0);
            out.put("recordId", blocked == null ? null : blocked.getId());
            return out;
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("question", question);
        placeholders.put("timeHint", buildTimeHint());
        placeholders.put("fieldInfo", buildFieldInfo(cfg));
        placeholders.put("serviceList", String.join(", ", serviceList));

        LlmSchemaRetryService.SchemaCheckedResult schemaResult;
        try {
            schemaResult = schemaRetryService.callWithSchema(
                    client, provider.getModelName(), "nl2es_dsl", placeholders);
        } catch (BizException e) {
            log.warn("[nl2es_dsl] LLM schema retry fail q={} err={}", question, e.getMessage());
            NlQueryLog fail = persistLog(userId, question, null, 0, null,
                    VALIDATION_FEEDBACK_RETRIES, System.currentTimeMillis() - t0, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsl", null);
            out.put("validated", false);
            out.put("errors", List.of("LLM 输出未通过 schema 校验：" + e.getMessage()));
            out.put("retryCount", VALIDATION_FEEDBACK_RETRIES);
            out.put("recordId", fail == null ? null : fail.getId());
            return out;
        }

        JsonNode dslJson = schemaResult.parsed();

        // 纳入 prompt 模板的"危险请求标识": 顶层 valid=false 或 blocked=true → validator 的语义拦截路径
        boolean llmBlocked = dslJson != null && dslJson.isObject()
                && (dslJson.path("blocked").asBoolean(false)
                    || (dslJson.path("valid").isBoolean() && !dslJson.path("valid").asBoolean()));
        if (llmBlocked) {
            String reason = dslJson.path("reason").asText("问题被标记为破坏性操作");
            log.warn("[nl2es_dsl] LLM 标记为危险请求 q={} reason={}",
                    (question == null ? "" : (question.length() > 60 ? question.substring(0, 60) + "…" : question)), reason);
            NlQueryLog fail = persistLog(userId, question, dslJson.toString(), 0, null,
                    0, System.currentTimeMillis() - t0, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsl", dslJson);
            out.put("validated", false);
            out.put("llmBlocked", true);
            out.put("reason", reason);
            out.put("errors", List.of(reason));
            out.put("retryCount", 0);
            out.put("recordId", fail == null ? null : fail.getId());
            return out;
        }

        DslSafetyValidator.ValidationResult vr = DslSafetyValidator.validate(dslJson, cfg, serviceList);
        int retryCount = schemaResult.attempts() - 1;

        // ---- 校验失败 → feedback retry（最多 2 次额外） ----
        int feedbackUsed = 0;
        while (!vr.ok() && feedbackUsed < VALIDATION_FEEDBACK_RETRIES) {
            feedbackUsed++;
            String errHint = "你上次输出的 ES DSL 未通过安全校验："
                    + String.join("; ", vr.errors())
                    + "。请重写为一个合法 DSL JSON（仅 query/sort/size/aggs/track_total_hits/from/timeout，可引用字段仅限 timeField/messageField/levelField/serviceField/traceIdField），禁止任何 script / _update / _delete 等。";
            Map<String, String> retryPh = new HashMap<>(placeholders);
            retryPh.put("question", question + "\n\n[错误反馈] " + errHint);
            try {
                LlmSchemaRetryService.SchemaCheckedResult r2 = schemaRetryService.callWithSchema(
                        client, provider.getModelName(), "nl2es_dsl", retryPh);
                dslJson = r2.parsed();
                vr = DslSafetyValidator.validate(dslJson, cfg, serviceList);
                retryCount += r2.attempts();
            } catch (BizException e) {
                log.warn("[nl2es_dsl] feedback retry#{} LLM fail: {}", feedbackUsed, e.getMessage());
                retryCount += 1;
            }
        }

        long latency = System.currentTimeMillis() - t0;
        if (vr.ok()) {
            NlQueryLog saved = persistLog(userId, question,
                    dslJson.toString(), 1, null, retryCount + feedbackUsed, latency, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsl", vr.dsl());
            out.put("validated", true);
            out.put("retryCount", retryCount + feedbackUsed);
            out.put("recordId", saved == null ? null : saved.getId());
            return out;
        }
        NlQueryLog saved = persistLog(userId, question,
                dslJson == null ? null : dslJson.toString(), 0, null, retryCount + feedbackUsed, latency, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dsl", dslJson);
        out.put("validated", false);
        out.put("errors", vr.errors());
        out.put("retryCount", retryCount + feedbackUsed);
        out.put("recordId", saved == null ? null : saved.getId());
        return out;
    }

    /* ================== 2) execute ================== */

    /**
     * 执行已 validate=1 的 nl_query_log：取出 generated_dsl → 二次校验（防 DB 篡改）→ 真实 _search。
     */
    public Map<String, Object> execute(Long userId, Long logId) {
        if (logId == null) {
            throw new BizException("recordId 必填");
        }
        NlQueryLog rec = nlQueryLogMapper.selectById(logId);
        if (rec == null) {
            throw new BizException("nl_query_log 不存在：id=" + logId);
        }
        if (rec.getValidated() == null || rec.getValidated() != 1) {
            throw new BizException("该记录未通过 DSL 校验，不可执行");
        }
        if (rec.getGeneratedDsl() == null || rec.getGeneratedDsl().isBlank()) {
            throw new BizException("generated_dsl 为空");
        }
        EsIndexConfig cfg = loadIndexConfig();
        List<String> serviceList = listKnownServices(cfg);

        JsonNode dsl;
        try {
            dsl = objectMapper.readTree(rec.getGeneratedDsl());
        } catch (Exception e) {
            throw new BizException("generated_dsl 非法 JSON：" + e.getMessage());
        }
        // 二次校验：防御 DB 中的 DSL 已被外部修改（安全红线）
        DslSafetyValidator.ValidationResult vr = DslSafetyValidator.validate(dsl, cfg, serviceList);
        if (!vr.ok()) {
            throw new BizException("二次校验未通过：" + String.join("; ", vr.errors()));
        }
        EsDatasource ds = esDatasourceMapper.selectById(1L);
        if (ds == null) {
            throw new BizException("ES 数据源未配置");
        }

        long t0 = System.currentTimeMillis();
        String dslBody;
        try {
            dslBody = objectMapper.writeValueAsString(vr.dsl());
        } catch (Exception e) {
            throw new BizException("DSL 序列化失败：" + e.getMessage());
        }
        String resp = esLogClient.postSearch(ds.baseUrl(), cfg.getIndexPattern() + "/_search", dslBody);
        long latency = System.currentTimeMillis() - t0;

        Map<String, Object> out;
        int hitCount = 0;
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode hits = root.path("hits");
            long total = hits.path("total").path("value").asLong(0);
            hitCount = hits.path("hits") != null && hits.path("hits").isArray()
                    ? hits.path("hits").size() : 0;

            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> row = new HashMap<>();
                row.put("time", src.path(cfg.getTimeField() == null ? "@timestamp" : cfg.getTimeField()).asText(null));
                row.put("level", src.path(cfg.getLevelField() == null ? "level" : cfg.getLevelField()).asText(null));
                row.put("service", src.path(cfg.getServiceField() == null ? "service" : cfg.getServiceField()).asText(null));
                row.put("traceId", src.path(cfg.getTraceIdField() == null ? "traceId" : cfg.getTraceIdField()).asText(null));
                row.put("message", src.path(cfg.getMessageField() == null ? "message" : cfg.getMessageField()).asText(null));
                records.add(row);
            }
            Map<String, Long> serviceCounts = extractServiceCounts(root, cfg);
            String answer = "命中 " + total + " 条，返回 " + hitCount + " 条；服务分布 " + serviceCounts;

            out = new LinkedHashMap<>();
            out.put("hits", records);
            out.put("total", total);
            out.put("serviceCounts", serviceCounts);
            out.put("elapsedMs", latency);
            out.put("answer", answer);

            // 回写 nl_query_log（hitCount + executed + answer）
            NlQueryLog upd = new NlQueryLog();
            upd.setId(rec.getId());
            upd.setExecuted(1);
            upd.setHitCount(hitCount);
            upd.setAnswer(answer);
            upd.setLatencyMs(latency);
            nlQueryLogMapper.updateById(upd);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("ES 响应解析失败：" + e.getMessage());
        }
        return out;
    }

    /* ================== 3) history ================== */

    public List<NlQueryLog> history(Long userId, int limit) {
        int effective = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 100);
        LambdaQueryWrapper<NlQueryLog> w = new LambdaQueryWrapper<>();
        if (userId != null) {
            w.eq(NlQueryLog::getUserId, userId);
        }
        w.orderByDesc(NlQueryLog::getCreateTime).last("LIMIT " + effective);
        List<NlQueryLog> list = nlQueryLogMapper.selectList(w);
        return list == null ? List.of() : list;
    }

    /* ================== 内部 ================== */

    private EsIndexConfig loadIndexConfig() {
        EsIndexConfig cfg = esIndexConfigMapper.selectById(1L);
        if (cfg == null) {
            // fallback：按 enabled=1 取第一条
            cfg = esIndexConfigMapper.selectOne(new LambdaQueryWrapper<EsIndexConfig>()
                    .eq(EsIndexConfig::getEnabled, 1)
                    .last("LIMIT 1"));
        }
        if (cfg == null) {
            throw new BizException("ES 索引配置未找到（es_index_config 需先配置）");
        }
        return cfg;
    }

    private List<String> listKnownServices(EsIndexConfig cfg) {
        Set<String> services = new HashSet<>();
        if (cfg == null || cfg.getServiceField() == null) {
            return List.of();
        }
        try {
            EsDatasource ds = esDatasourceMapper.selectById(cfg.getDatasourceId() == null ? 1L : cfg.getDatasourceId());
            if (ds == null) return List.of();
            // terms agg：size=50
            String dsl = "{\n  \"size\":0,\n  \"aggs\":{\"svc\":{\"terms\":{\"field\":\""
                    + cfg.getServiceField()
                    + ".keyword\",\"size\":50}}}\n}";
            String resp = esLogClient.postSearch(ds.baseUrl(), cfg.getIndexPattern() + "/_search", dsl);
            JsonNode root = objectMapper.readTree(resp);
            JsonNode buckets = root.path("aggregations").path("svc").path("buckets");
            if (buckets.isArray()) {
                for (JsonNode b : buckets) {
                    String k = b.path("key").asText(null);
                    if (k != null && !k.isBlank()) services.add(k);
                }
            }
        } catch (Exception e) {
            log.warn("[nl2es_dsl] listKnownServices fail serviceField={}: {}", cfg.getServiceField(), e.getMessage());
        }
        return new ArrayList<>(services);
    }

    private LlmProvider pickProvider() {
        LlmProvider p = llmProviderMapper.selectOne(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getIsDefault, 1)
                .last("LIMIT 1"));
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用");
        }
        return p;
    }

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String buildTimeHint() {
        LocalDateTime now = LocalDateTime.now();
        return "now=" + now.format(F)
                + ", 1h_ago=" + now.minusHours(1).format(F)
                + ", 24h_ago=" + now.minusHours(24).format(F);
    }

    private String buildFieldInfo(EsIndexConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("timeField=").append(nullSafe(cfg.getTimeField())).append('\n');
        sb.append("messageField=").append(nullSafe(cfg.getMessageField())).append('\n');
        sb.append("levelField=").append(nullSafe(cfg.getLevelField())).append('\n');
        sb.append("serviceField=").append(nullSafe(cfg.getServiceField())).append('\n');
        sb.append("traceIdField=").append(nullSafe(cfg.getTraceIdField())).append('\n');
        sb.append("规则：term/terms 精确匹配请用 <field>.keyword；range 仅可应用于 timeField；")
                .append("聚合只允许 terms / date_histogram / avg / max / min / sum。");
        return sb.toString();
    }

    private Map<String, Long> extractServiceCounts(JsonNode root, EsIndexConfig cfg) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (cfg == null || cfg.getServiceField() == null) return counts;
        JsonNode buckets = root.path("aggregations")
                .path("by_service")
                .path("buckets");
        if (buckets.isArray()) {
            for (JsonNode b : buckets) {
                counts.put(b.path("key").asText(""), b.path("doc_count").asLong(0));
            }
        }
        return counts;
    }

    private NlQueryLog persistLog(Long userId, String question, String generatedDsl,
                                  Integer validated, Integer executed,
                                  Integer retryCount, Long latencyMs, String answer) {
        try {
            NlQueryLog l = new NlQueryLog();
            l.setUserId(userId);
            l.setQuestion(question);
            l.setGeneratedDsl(generatedDsl);
            l.setValidated(validated);
            l.setExecuted(executed == null ? 0 : executed);
            l.setHitCount(null);
            l.setAnswer(answer);
            l.setRetryCount(retryCount);
            l.setLatencyMs(latencyMs);
            l.setCreateTime(LocalDateTime.now());
            nlQueryLogMapper.insert(l);
            return l;
        } catch (Exception e) {
            log.warn("[nl2es_dsl] 落 nl_query_log 失败（不影响主流程）：{}", e.getMessage());
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
