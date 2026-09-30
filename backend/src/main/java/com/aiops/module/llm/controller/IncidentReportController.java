package com.aiops.module.llm.controller;

import com.aiops.common.Result;
import com.aiops.module.llm.service.IncidentReportService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M5-10 故障报告生成。
 * <p>
 * 契约：GET /api/ai/report/{incidentId}
 * 返回 {code:200, data:{markdown, cached, attempts, latencyMs, totalTokens, llm_call_log_id}}
 * 首次调用 → 调 LLM 写 alert_incident.llm_report；
 * 二次及以上 → 返回缓存（cached=true），不调 LLM，不写新 llm_call_log。
 */
@Slf4j
@Tag(name = "AI 故障报告")
@RestController
@RequestMapping("/api/ai/report")
@RequiredArgsConstructor
public class IncidentReportController {

    private final IncidentReportService incidentReportService;

    @Operation(summary = "生成 / 取缓存 故障报告 Markdown")
    @RequirePerm("incident:list")
    @GetMapping("/{incidentId}")
    public Result<Map<String, Object>> report(@PathVariable long incidentId) {
        IncidentReportService.ReportResult r = incidentReportService.generateReport(incidentId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", r.markdown());
        data.put("cached", r.cached());
        data.put("attempts", r.attempts());
        data.put("latencyMs", r.latencyMs());
        data.put("totalTokens", r.totalTokens());
        if (r.llmCallLogId() != null) {
            data.put("llm_call_log_id", r.llmCallLogId());
        }
        return Result.ok(data);
    }
}
