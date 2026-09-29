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

    @Operation(summary = "AI 解读（模板/异常），输出 schema 校验后落库 log_analysis_record")
    @RequirePerm("log:search:exec")
    @PostMapping("/explain")
    public Result<Map<String, Object>> explain(@RequestBody Map<String, Object> body) {
        Object scene = body.get("scene");
        Object refId = body.get("refId");
        if (scene == null || refId == null) {
            throw new BizException("scene / refId 必填");
        }
        Long rid = refId instanceof Number ? ((Number) refId).longValue()
                : Long.parseLong(String.valueOf(refId));
        return Result.ok(aiLogExplainService.explain(String.valueOf(scene), rid));
    }
}
