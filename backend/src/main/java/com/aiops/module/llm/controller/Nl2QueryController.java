package com.aiops.module.llm.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.llm.service.Nl2QueryService;
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
 * M5-9 自然语言→查询。
 * <p>
 * POST /api/ai/nl2query
 *   body: {"question":"过去一小时 cpu 最高的服务是谁"}
 *   响应: {code:200, data:{query:{...}, results:[...], explain:"...", rawLlm:"...", isLlmFallback:false, attempts:1}}
 * 当 LLM 失败 / schema 校验失败 / 网络异常，data.isLlmFallback=true 且 query.queryType=unknown，并附 3 个示例问题。
 */
@Slf4j
@Tag(name = "AI 自然语言查询")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class Nl2QueryController {

    private final Nl2QueryService nl2QueryService;

    @Operation(summary = "自然语言→结构化查询（M5-9），未知问题返回兜底+示例")
    @RequirePerm("ai:chat")
    @PostMapping("/nl2query")
    public Result<Map<String, Object>> nl2query(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new BizException("body 不能为空");
        }
        Object q = body.get("question");
        if (q == null || String.valueOf(q).isBlank()) {
            throw new BizException("question 不能为空");
        }
        Map<String, Object> data = nl2QueryService.nl2query(String.valueOf(q));
        return Result.ok(data);
    }
}
