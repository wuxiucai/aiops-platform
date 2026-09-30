package com.aiops.module.kb.controller;

import com.aiops.common.Result;
import com.aiops.module.kb.service.KbCaseService;
import com.aiops.module.kb.service.SimilarCaseService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * M5-11 知识库相似案例管理端点。
 */
@Slf4j
@Tag(name = "知识库 相似案例")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KbCaseController {

    private final KbCaseService kbCaseService;
    private final SimilarCaseService similarCaseService;

    @Operation(summary = "回填 kb_fault_case.embedding")
    @RequirePerm("kb:case:list")
    @PostMapping("/kb/case/sync-embeddings")
    public Result<Map<String, Object>> syncEmbeddings() {
        return Result.ok(kbCaseService.syncEmbeddings());
    }

    @Operation(summary = "按 incident 检索相似案例")
    @RequirePerm("incident:list")
    @PostMapping("/ai/similar-case")
    public Result<Map<String, Object>> findSimilarCases(@RequestBody Map<String, Object> body) {
        Object incId = body.get("incidentId");
        if (incId == null) {
            throw new com.aiops.common.BizException("incidentId 必填");
        }
        long id = incId instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(incId));
        int topK = body.get("topK") instanceof Number n ? n.intValue() : 3;
        return Result.ok(similarCaseService.findSimilarCases(id, topK));
    }
}
