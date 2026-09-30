package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.service.AiScenarioService;
import com.aiops.module.log.service.AiLogExplainService;
import com.aiops.security.RequirePerm;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 日志解读
 */
@Slf4j
@Tag(name = "AI 日志解读")
@RestController
@RequestMapping("/api/log/ai")
@RequiredArgsConstructor
public class AiLogExplainController {

    private final AiLogExplainService aiLogExplainService;
    private final AiScenarioService aiScenarioService;

    /**
     * AI 解读（模板/异常），schema 校验后落库 log_analysis_record。
     * <p>
     * 契约（v2 §3.8 约定 + 希望审查方 curl 例子对齐）：
     *   请求：{ "templateId": 12, "sceneCode": "log_explain" }
     *      或老契约 { "scene": "template_explain", "refId": 12 }
     *   返回：{ summary, likelyCause, suggestion, confidence, recordId, latencyMs, tokenCost }
     */
    @Operation(summary = "AI 解读（模板/异常），输出 schema 校验后落库 log_analysis_record")
    @RequirePerm("log:search:exec")
    @PostMapping("/explain")
    public Result<Map<String, Object>> explain(@RequestBody Map<String, Object> body) {
        // 双契约对齐
        Object refId = body.get("templateId") != null ? body.get("templateId") : body.get("refId");
        Object sceneCode = body.get("sceneCode") != null ? body.get("sceneCode") : body.get("scene");
        if (refId == null) {
            throw new BizException("templateId / refId 必填");
        }
        String scene = sceneCode == null ? "template_explain" : String.valueOf(sceneCode);
        // sceneCode 规格化：log_explain → template_explain（AiLogExplainService 内部按 template_explain 取模板上下文）
        if ("log_explain".equalsIgnoreCase(scene)) {
            scene = "template_explain";
        }
        Long rid = refId instanceof Number ? ((Number) refId).longValue()
                : Long.parseLong(String.valueOf(refId));
        // 用 M5 统一场景链路（含 schema 校验 + 重试 + llm_call_log）
        JsonNode parsed;
        try {
            parsed = aiScenarioService.logExplain(rid);
        } catch (Exception e) {
            throw new BizException("AI 解读失败：" + e.getMessage());
        }
        // 扁平化返回
        Map<String, Object> out = new java.util.HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(parsed, Map.class);
            out.putAll(m);
        } catch (IllegalArgumentException ignore) {
            //
        }
        return Result.ok(out);
    }
}
