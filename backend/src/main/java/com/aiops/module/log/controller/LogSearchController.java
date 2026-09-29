package com.aiops.module.log.controller;

import com.aiops.common.Result;
import com.aiops.module.log.service.LogSearchService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "日志检索")
@RestController
@RequestMapping("/api/log/search")
@RequiredArgsConstructor
public class LogSearchController {

    private final LogSearchService logSearchService;

    @Operation(summary = "多条件检索 + 高亮 + 返回 DSL")
    @RequirePerm("log:search:exec")
    @PostMapping
    public Result<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        return Result.ok(logSearchService.search(body));
    }

    @Operation(summary = "时间直方图")
    @RequirePerm("log:search:exec")
    @PostMapping("/histogram")
    public Result<Map<String, Object>> histogram(@RequestBody Map<String, Object> body) {
        return Result.ok(logSearchService.histogram(body));
    }
}
