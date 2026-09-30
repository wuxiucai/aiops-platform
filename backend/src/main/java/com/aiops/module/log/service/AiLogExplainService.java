package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmProvider;
import com.aiops.module.llm.mapper.LlmProviderMapper;
import com.aiops.module.llm.service.LlmProviderService;
import com.aiops.module.log.entity.LogAnalysisRecord;
import com.aiops.module.log.entity.LogAnomaly;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.mapper.LogAnalysisRecordMapper;
import com.aiops.module.log.mapper.LogAnomalyMapper;
import com.aiops.module.log.mapper.LogTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * AI 日志解读：模板解读 + 异常解读。
 * LLM 必须返回严格 JSON：summary / likelyCause / suggestion / confidence。
 * 通过 schema 校验后落表 log_analysis_record。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiLogExplainService {

    private final LlmProviderMapper llmProviderMapper;
    private final LlmProviderService llmProviderService;
    private final LogTemplateMapper logTemplateMapper;
    private final LogAnomalyMapper logAnomalyMapper;
    private final LogAnalysisRecordMapper logAnalysisRecordMapper;
    private final com.aiops.module.llm.mapper.LlmCallLogMapper llmCallLogMapper;
    private final com.aiops.module.llm.mapper.LlmPromptTemplateMapper llmPromptTemplateMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final String OUTPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["summary", "likelyCause", "suggestion", "confidence"],
              "properties": {
                "summary":     {"type": "string", "minLength": 1},
                "likelyCause": {"type": "string", "minLength": 1},
                "suggestion":  {"type": "string", "minLength": 1},
                "confidence":  {"type": "number", "minimum": 0, "maximum": 1}
              },
              "additionalProperties": true
            }
            """;

    public Map<String, Object> explain(String scene, Long refId) {
        String inputSummary = buildInputSummary(scene, refId);

        LlmProvider provider = llmProviderMapper.selectOne(
                new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getIsDefault, 1)
                        .last("LIMIT 1"));
        if (provider == null || provider.getStatus() == null || provider.getStatus() != 1) {
            throw new BizException("默认 LLM Provider 未启用，请先在 /llm/provider 配置");
        }
        LlmClient client = llmProviderService.buildClient(provider);

        // 任务书 §5.7：提示词模板化管理 —— 从 llm_prompt_template 读取，scene=log_explain
        com.aiops.module.llm.entity.LlmPromptTemplate tpl = llmPromptTemplateMapper.selectOne(
                new LambdaQueryWrapper<com.aiops.module.llm.entity.LlmPromptTemplate>()
                        .eq(com.aiops.module.llm.entity.LlmPromptTemplate::getSceneCode, "log_explain")
                        .eq(com.aiops.module.llm.entity.LlmPromptTemplate::getEnabled, 1)
                        .last("LIMIT 1"));
        String systemPrompt = tpl != null && tpl.getSystemPrompt() != null && !tpl.getSystemPrompt().isBlank()
                ? tpl.getSystemPrompt()
                : "你是 AIOps 日志分析专家。请严格以 JSON 对象回答，字段为 summary, likelyCause, suggestion, confidence，confidence 取值范围 [0,1]。不要输出 markdown 围栏。";
        String userTpl = tpl != null && tpl.getUserPromptTpl() != null && !tpl.getUserPromptTpl().isBlank()
                ? tpl.getUserPromptTpl()
                : "分析以下日志模板异常情况：\n\n${inputSummary}\n\n请只返回严格的 JSON，无任何前置/后续文本。";
        userTpl = userTpl.replace("${inputSummary}", inputSummary);

        List<LlmClient.LlmMessage> msgs = new ArrayList<>();
        msgs.add(new LlmClient.LlmMessage("system", systemPrompt));
        msgs.add(new LlmClient.LlmMessage("user", userTpl));

        LlmClient.LlmRequest req = new LlmClient.LlmRequest(
                provider.getModelName(), msgs, 0.3, 1024, false);

        long t0 = System.currentTimeMillis();
        LlmClient.LlmResponse resp;
        try {
            resp = client.chat(req);
        } catch (Exception e) {
            log.error("[AI解读] LLM 调用失败 scene={}, refId={}, err={}", scene, refId, e.getMessage());
            saveCallLog(provider.getId(), scene, refId, 0, 0, 0,
                    System.currentTimeMillis() - t0, "fail", e.getMessage());
            throw new BizException("LLM 调用失败：" + e.getMessage());
        }
        long latency = System.currentTimeMillis() - t0;
        // 任务书红线：每次 LLM 调用都落 llm_call_log（含 cost/latency），场景跑通后才解析
        saveCallLog(provider.getId(), scene, refId,
                resp == null ? 0 : resp.promptTokens(),
                resp == null ? 0 : resp.completionTokens(),
                resp == null ? 0 : resp.totalTokens(),
                latency, "success", null);
        String content = resp == null ? null : resp.content();
        log.info("[AI解读] scene={}, refId={}, latency={}ms, contentLen={}",
                scene, refId, latency, content == null ? 0 : content.length());

        String json = extractJson(content);
        if (json == null) {
            throw new BizException("LLM 返回不含 JSON 对象");
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BizException("LLM 返回不是合法 JSON：" + e.getMessage());
        }
        String err = validateSchema(parsed);
        if (err != null) {
            log.error("[AI解读] Schema 校验失败：{}；content={}", err, content);
            throw new BizException("LLM 输出不符合 schema：" + err);
        }

        LocalDateTime now = LocalDateTime.now();
        LogAnalysisRecord rec = new LogAnalysisRecord();
        rec.setDatasourceId(1L);
        rec.setIndexConfigId(1L);
        rec.setSceneCode(scene);
        rec.setRefId(refId);
        rec.setTimeStart(now.minusHours(1));
        rec.setTimeEnd(now);
        rec.setLogCount(0);
        rec.setTemplateCount(1);
        rec.setInputSummary(inputSummary);
        rec.setResult(json);
        rec.setTokenCost(resp.totalTokens());
        rec.setLatencyMs(latency);
        rec.setStatus("success");
        rec.setCreateTime(now);
        logAnalysisRecordMapper.insert(rec);

        Map<String, Object> out = new HashMap<>();
        out.put("analysis", parsed);
        out.put("recordId", rec.getId());
        out.put("latencyMs", latency);
        out.put("tokenCost", rec.getTokenCost());
        out.put("schema", OUTPUT_SCHEMA);
        return out;
    }

    private String buildInputSummary(String scene, Long refId) {
        if ("template_explain".equals(scene)) {
            LogTemplate t = logTemplateMapper.selectById(refId);
            if (t == null) {
                throw new BizException("模板不存在：id=" + refId);
            }
            return String.join("\n",
                    "- 模板 id：" + t.getId(),
                    "- 服务：" + nullSafe(t.getService()),
                    "- 日志级别：" + nullSafe(t.getLevel()),
                    "- 模板内容：" + nullSafe(t.getTemplateText()),
                    "- token 数：" + t.getTokenCount(),
                    "- 累计出现：" + t.getTotalCount(),
                    "- 最近窗口出现：" + t.getLastWindowCount(),
                    "- 首次出现：" + t.getFirstSeen(),
                    "- 最近出现：" + t.getLastSeen(),
                    "- 样本日志：" + nullSafe(t.getSampleLog()));
        }
        if ("anomaly_explain".equals(scene)) {
            LogAnomaly a = logAnomalyMapper.selectById(refId);
            if (a == null) {
                throw new BizException("异常不存在：id=" + refId);
            }
            LogTemplate t = a.getTemplateId() == null ? null
                    : logTemplateMapper.selectById(a.getTemplateId());
            return String.join("\n",
                    "- 异常 id：" + a.getId(),
                    "- 异常类型：" + nullSafe(a.getAnomalyType()),
                    "- 级别：" + nullSafe(a.getLevel()),
                    "- 标题：" + nullSafe(a.getTitle()),
                    "- 描述：" + nullSafe(a.getDescription()),
                    "- 触发值：" + a.getTriggerValue(),
                    "- 基线值：" + a.getBaselineValue(),
                    "- 累计：" + a.getCount(),
                    "- 首次：" + a.getFirstTime(),
                    "- 最近：" + a.getLastTime(),
                    t == null ? "" : "- 关联模板：" + nullSafe(t.getTemplateText()),
                    t == null ? "" : "- 模板样本：" + nullSafe(t.getSampleLog()));
        }
        throw new BizException("不支持的 scene：" + scene);
    }

    private String nullSafe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            return s;
        }
        if (s.startsWith("```")) {
            int head = s.indexOf('\n');
            int tail = s.lastIndexOf("```");
            if (head > 0 && tail > head) {
                String body = s.substring(head + 1, tail).trim();
                if (body.startsWith("{") && body.endsWith("}")) {
                    return body;
                }
            }
        }
        int i = s.indexOf('{');
        int j = s.lastIndexOf('}');
        if (i >= 0 && j > i) {
            return s.substring(i, j + 1);
        }
        return null;
    }

    private String validateSchema(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "root must be object";
        }
        String[] required = {"summary", "likelyCause", "suggestion"};
        for (String f : required) {
            JsonNode v = node.get(f);
            if (v == null || !v.isTextual() || v.asText().isBlank()) {
                return "missing or empty: " + f;
            }
        }
        JsonNode c = node.get("confidence");
        if (c == null || !c.isNumber()) {
            return "confidence must be number";
        }
        double cv = c.asDouble();
        if (cv < 0 || cv > 1) {
            return "confidence must be in [0,1]";
        }
        return null;
    }

    /**
     * 任务书 §5.7 "记 llm_call_log"。DeepSeek ¥0.01/1K tokens → 估算成本（元）。
     * 不阻塞主流程：落库失败只打 warning。
     */
    private void saveCallLog(Long providerId, String sceneCode, Long refId,
                             int promptTokens, int completionTokens, int totalTokens,
                             long latencyMs, String status, String errorMsg) {
        try {
            com.aiops.module.llm.entity.LlmCallLog log_ = new com.aiops.module.llm.entity.LlmCallLog();
            log_.setProviderId(providerId);
            log_.setSceneCode(sceneCode);
            log_.setRefId(refId);
            log_.setPromptTokens(promptTokens);
            log_.setCompletionTokens(completionTokens);
            log_.setTotalTokens(totalTokens);
            log_.setLatencyMs(latencyMs);
            log_.setStatus(status);
            log_.setErrorMsg(errorMsg);
            log_.setCreateTime(LocalDateTime.now());
            llmCallLogMapper.insert(log_);
        } catch (Exception e) {
            log.warn("[AI解读] 落 llm_call_log 失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
