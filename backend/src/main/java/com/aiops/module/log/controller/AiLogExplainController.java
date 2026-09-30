package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.service.AiLogExplainService;
import com.aiops.security.RequirePerm;
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
        Map<String, Object> raw = aiLogExplainService.explain(scene, rid);
        // 扁平化返回：把 analysis 的四个字段提到顶层，匹配审查方预期 schema。
        // 注意：service 里 analysis 是 Jackson JsonNode（schema 校验用），需转 Map。
        Object analysisObj = raw.get("analysis");
        Map<String, Object> out = new java.util.HashMap<>();
        if (analysisObj instanceof com.fasterxml.jackson.databind.JsonNode node) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(node, Map.class);
                out.putAll(m);
            } catch (IllegalArgumentException ignore) {
                // 极端情况： convert 失败就退化为不扁平化
            }
        } else if (analysisObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) analysisObj;
            out.putAll(m);
        }
        out.put("recordId", raw.get("recordId"));
        out.put("latencyMs", raw.get("latencyMs"));
        out.put("tokenCost", raw.get("tokenCost"));
        out.put("schema", raw.get("schema"));
        return Result.ok(out);
    }
}
