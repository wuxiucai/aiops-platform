package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.service.AiScenarioService;
import com.aiops.security.RequirePerm;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * M5-8 统一 AI 场景路由：alert_explain / root_cause / log_explain。
 * 契约（一次调用，同步返回解析好的 JSON；schema 失败时返回 error 带错误原因）。
 */
@Slf4j
@Tag(name = "AI 场景调用")
@RestController
@RequestMapping("/api/ai/scenario")
@RequiredArgsConstructor
public class AiScenarioController {

    private final AiScenarioService aiScenarioService;

    @Operation(summary = "AI 告警解读：GET /api/ai/scenario/alert-explain/{alertId}")
    @RequirePerm("alert:record:list")
    @GetMapping("/alert-explain/{alertId}")
    public Result<Map<String, Object>> alertExplain(@PathVariable long alertId) {
        JsonNode n = aiScenarioService.alertExplain(alertId);
        return Result.ok(toMap(n));
    }

    @Operation(summary = "AI 根因分析：GET /api/ai/scenario/root-cause/{incidentId}")
    @RequirePerm("incident:list")
    @GetMapping("/root-cause/{incidentId}")
    public Result<Map<String, Object>> rootCause(@PathVariable long incidentId) {
        JsonNode n = aiScenarioService.rootCauseAnalysis(incidentId);
        return Result.ok(toMap(n));
    }

    /* ================== 内部 ================== */

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode n) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            return om.convertValue(n, Map.class);
        } catch (IllegalArgumentException e) {
            throw new BizException("结果转换失败: " + e.getMessage());
        }
    }
}
