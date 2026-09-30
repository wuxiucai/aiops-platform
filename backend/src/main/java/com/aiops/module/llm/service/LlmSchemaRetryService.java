package com.aiops.module.llm.service;

import com.aiops.common.BizException;
import com.aiops.module.llm.client.LlmClient;
import com.aiops.module.llm.entity.LlmPromptTemplate;
import com.aiops.module.llm.mapper.LlmPromptTemplateMapper;
import com.aiops.module.llm.validator.OutputSchemaValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M5-6 核心：带 schema 校验的 LLM 调用服务。
 * <p>
 * 流程：
 *   1. 读 prompt_template（每次从 DB，满足审查方要求 1）
 *   2. 调用 LlmClient.chat 取文本
 *   3. 剥 markdown 围栏 → JSON.parse
 *   4. 用 OutputSchemaValidator（JSON Schema 标准子集）校验
 *   5. 校验失败 → 透传错误原因进 user_prompt 让 LLM 自我修正，最多 2 次重试
 *   6. 都失败 → 抛 BizException，调用方按场景走兜底（§7.6）
 * <p>
 * 返回 {@link Result} 包含 parsed JSON + 原始 content + 重试次数 + 总 token，
 * 调用方负责落 llm_call_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSchemaRetryService {

    public static final int MAX_RETRIES = 2;

    private final LlmPromptTemplateMapper promptTemplateMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 结果容器 */
    public record SchemaCheckedResult(
            JsonNode parsed,
            String rawContent,
            int attempts,
            int totalPromptTokens,
            int totalCompletionTokens,
            int totalTokens,
            long totalLatencyMs) {}

    /**
     * 按 sceneCode 调用 LLM 并做 schema 校验。
     *
     * @param client   LlmClient 实例（必须 buildClient 好的）
     * @param model    model name
     * @param sceneCode 如 "log_explain"
     * @param inputSummary 用户输入摘要（会替换 ${inputSummary} 占位）
     */
    public SchemaCheckedResult callWithSchema(LlmClient client, String model,
                                              String sceneCode, String inputSummary) {
        LlmPromptTemplate tpl = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<LlmPromptTemplate>()
                        .eq(LlmPromptTemplate::getSceneCode, sceneCode)
                        .eq(LlmPromptTemplate::getEnabled, 1)
                        .last("LIMIT 1"));
        if (tpl == null) {
            throw new BizException("prompt 模板不存在：scene=" + sceneCode);
        }
        String systemPrompt = tpl.getSystemPrompt() == null ? "" : tpl.getSystemPrompt();
        String userTpl = tpl.getUserPromptTpl() == null ? "" : tpl.getUserPromptTpl();
        String schema = tpl.getOutputSchema();

        String userMsg = userTpl.replace("${inputSummary}", inputSummary == null ? "" : inputSummary);

        long t0 = System.currentTimeMillis();
        int sumPrompt = 0, sumComp = 0, sumTotal = 0;
        String lastErr = null;
        String lastContent = null;

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            List<LlmClient.LlmMessage> msgs = List.of(
                    new LlmClient.LlmMessage("system", systemPrompt),
                    new LlmClient.LlmMessage("user", userMsg));

            LlmClient.LlmRequest req = new LlmClient.LlmRequest(model, msgs, 0.3, 2048, false);
            LlmClient.LlmResponse resp;
            try {
                resp = client.chat(req);
            } catch (Exception e) {
                lastErr = "LLM 调用失败: " + e.getMessage();
                log.warn("[LlmSchemaRetry] scene={} attempt={} err={}", sceneCode, attempt, lastErr);
                if (attempt > MAX_RETRIES) break;
                continue;
            }
            if (resp != null) {
                sumPrompt += resp.promptTokens();
                sumComp += resp.completionTokens();
                sumTotal += resp.totalTokens();
            }
            String content = resp == null ? null : resp.content();
            lastContent = content;
            String jsonText = extractJson(content);
            if (jsonText == null) {
                lastErr = "LLM 返回不含 JSON 对象";
                log.warn("[LlmSchemaRetry] scene={} attempt={} no-json content={}",
                        sceneCode, attempt, abbreviate(content, 200));
                userMsg = retryHint(lastErr, schema);
                continue;
            }
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(jsonText);
            } catch (Exception e) {
                lastErr = "JSON 解析失败: " + e.getMessage();
                log.warn("[LlmSchemaRetry] scene={} attempt={} json-parse-fail", sceneCode, attempt);
                userMsg = retryHint(lastErr, schema);
                continue;
            }
            String schemaErr = OutputSchemaValidator.validate(schema, parsed);
            if (schemaErr == null) {
                long latency = System.currentTimeMillis() - t0;
                log.info("[LlmSchemaRetry] scene={} attempt={} success latency={}ms tokens={}",
                        sceneCode, attempt, latency, sumTotal);
                return new SchemaCheckedResult(parsed, content, attempt,
                        sumPrompt, sumComp, sumTotal, latency);
            }
            lastErr = schemaErr;
            log.warn("[LlmSchemaRetry] scene={} attempt={} schemaErr={}", sceneCode, attempt, schemaErr);
            userMsg = retryHint(schemaErr, schema);
        }
        throw new BizException("LLM 输出连续 " + (MAX_RETRIES + 1) + " 次未通过 schema 校验："
                + lastErr + "；最后一次返回=" + abbreviate(lastContent, 300));
    }

    /** markdown 围栏 / 前置文本容错：找出第一个 {..} JSON 块 */
    public static String extractJson(String content) {
        if (content == null) return null;
        String s = content.trim();
        // 1. ```json ... ``` / ``` ... ``` 剥离
        if (s.startsWith("```")) {
            Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(s);
            if (m.find()) s = m.group(1).trim();
        }
        // 2. 找到第一个 { ... } 完整块
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String retryHint(String lastErr, String schema) {
        return "你上次的输出未通过 schema 校验：" + lastErr
                + "。期望的 schema 是：\n" + (schema == null ? "(未提供)" : schema)
                + "\n请严格按 schema 重新输出一个 JSON 对象，不要输出额外文本。";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
