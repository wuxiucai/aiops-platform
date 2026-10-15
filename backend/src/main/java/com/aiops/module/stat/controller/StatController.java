package com.aiops.module.stat.controller;

import com.aiops.common.Result;
import com.aiops.module.stat.service.StatService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统计分析：平台效果指标 + LLM 成本。查询实现见 {@link StatService}。
 */
@Tag(name = "统计")
@RestController
@RequestMapping("/api/stat")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    /** 告警（含 MTTA/误报率）/ 事件（MTTR）/ 日志模板压缩率，近 days 天 */
    @Operation(summary = "效果指标")
    @RequirePerm("stat:effect")
    @GetMapping("/effect-metrics")
    public Result<Map<String, Object>> effect(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statService.effect(days));
    }

    /** 调用次数 / token / 成功率，按场景、provider、天分组，慢调用 top10 */
    @Operation(summary = "LLM 成本")
    @RequirePerm("stat:llmcost")
    @GetMapping("/llm-cost")
    public Result<Map<String, Object>> llmCost(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statService.llmCost(days));
    }
}
