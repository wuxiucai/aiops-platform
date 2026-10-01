package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.entity.NlQueryLog;
import com.aiops.module.log.service.Nl2DslService;
import com.aiops.security.LoginUser;
import com.aiops.security.RequirePerm;
import com.aiops.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M6-1/2/3: NL2ES-DSL
 * <ul>
 *   <li>POST /api/log/ai/nl2dsl            生成 DSL（仅校验，不执行）</li>
 *   <li>POST /api/log/ai/nl2dsl/execute    执行已 validated=1 的 DSL</li>
 *   <li>GET  /api/log/nl-query-history     最近查询历史</li>
 * </ul>
 */
@Slf4j
@Tag(name = "NL2ES-DSL 自然语言日志检索")
@RestController
@RequestMapping("/api/log")
@RequiredArgsConstructor
public class Nl2DslController {

    private final Nl2DslService nl2DslService;

    /** 生成 DSL（仅校验，不执行） */
    @Operation(summary = "生成 ES DSL（不执行），三重安全校验后返回 validated+dsl+recordId")
    @RequirePerm("log:search:exec")
    @PostMapping("/ai/nl2dsl")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        String question = body == null ? null
                : (body.get("question") == null ? null : String.valueOf(body.get("question")));
        if (question == null || question.isBlank()) {
            throw new BizException("question 不能为空");
        }
        Long userId = currentUserId();
        Map<String, Object> out = nl2DslService.generate(userId, question);
        return Result.ok(out);
    }

    /** 执行已 validated=1 的记录 */
    @Operation(summary = "执行 NL2DSL 生成的 DSL，落 hitCount/answer")
    @RequirePerm("log:search:exec")
    @PostMapping("/ai/nl2dsl/execute")
    public Result<Map<String, Object>> execute(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("recordId") == null) {
            throw new BizException("recordId 必填");
        }
        Long recordId = body.get("recordId") instanceof Number
                ? ((Number) body.get("recordId")).longValue()
                : Long.parseLong(String.valueOf(body.get("recordId")));
        Long userId = currentUserId();
        Map<String, Object> out = nl2DslService.execute(userId, recordId);
        return Result.ok(out);
    }

    /** 查询历史 */
    @Operation(summary = "最近 N 条 NL 查询历史（默认 20，最大 100）")
    @RequirePerm("log:search:exec")
    @GetMapping("/nl-query-history")
    public Result<List<NlQueryLog>> history(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return Result.ok(nl2DslService.history(currentUserId(), limit));
    }

    private static Long currentUserId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUserId();
    }
}
