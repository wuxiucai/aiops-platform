package com.aiops.module.log.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.service.LogTemplateService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "日志模板")
@RestController
@RequestMapping("/api/log/template")
@RequiredArgsConstructor
public class LogTemplateController {

    private final LogTemplateService logTemplateService;

    @Operation(summary = "分页")
    @RequirePerm("log:search:list")
    @GetMapping("/page")
    public Result<Page<LogTemplate>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String service) {
        return Result.ok(logTemplateService.page(current, size, status, keyword, service));
    }

    @Operation(summary = "切换状态：0 正常 1 关注 2 忽略")
    @RequirePerm("log:template:update")
    @PutMapping("/{id}/status")
    public Result<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        if (status == null || status < 0 || status > 2) {
            throw new BizException("status 仅允许 0/1/2");
        }
        logTemplateService.updateStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "趋势（最近 N 小时窗口计数）")
    @RequirePerm("log:search:list")
    @GetMapping("/{id}/trend")
    public Result<Map<String, Object>> trend(@PathVariable Long id,
                                             @RequestParam(required = false) Integer hours) {
        return Result.ok(logTemplateService.trend(id, hours));
    }

    @Operation(summary = "样本（按 keyword 查最近 size 条）")
    @RequirePerm("log:search:list")
    @GetMapping("/{id}/samples")
    public Result<Map<String, Object>> samples(@PathVariable Long id,
                                               @RequestParam(required = false) Integer size) {
        return Result.ok(logTemplateService.samples(id, size));
    }
}
