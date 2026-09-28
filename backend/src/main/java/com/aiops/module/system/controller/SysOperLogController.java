package com.aiops.module.system.controller;

import com.aiops.common.Result;
import com.aiops.module.system.entity.SysOperLog;
import com.aiops.module.system.mapper.SysOperLogMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志查询
 */
@Tag(name = "操作日志")
@RestController
@RequestMapping("/api/system/log")
@RequiredArgsConstructor
public class SysOperLogController {

    private final SysOperLogMapper sysOperLogMapper;

    @Operation(summary = "操作日志分页")
    @RequirePerm("system:log:list")
    @GetMapping("/page")
    public Result<Page<SysOperLog>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String module) {
        return Result.ok(sysOperLogMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysOperLog>()
                        .eq(module != null && !module.isBlank(), SysOperLog::getModule, module)
                        .orderByDesc(SysOperLog::getId)));
    }
}
