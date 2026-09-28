package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.aiops.module.monitor.service.MonitorService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 监控对象管理
 */
@Tag(name = "监控对象")
@RestController
@RequestMapping("/api/monitor/target")
@RequiredArgsConstructor
public class MonitorTargetController {

    private final MonitorTargetMapper monitorTargetMapper;
    private final MonitorService monitorService;

    @Operation(summary = "分页列表")
    @RequirePerm("monitor:target:list")
    @GetMapping("/page")
    public Result<Page<MonitorTarget>> page(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long groupId) {
        return Result.ok(monitorTargetMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MonitorTarget>()
                        .like(keyword != null && !keyword.isBlank(), MonitorTarget::getName, keyword)
                        .eq(groupId != null, MonitorTarget::getGroupId, groupId)
                        .orderByDesc(MonitorTarget::getId)));
    }

    @Operation(summary = "全部列表（下拉用）")
    @RequirePerm("monitor:target:list")
    @GetMapping("/list")
    public Result<List<MonitorTarget>> list() {
        return Result.ok(monitorTargetMapper.selectList(
                new LambdaQueryWrapper<MonitorTarget>().eq(MonitorTarget::getStatus, 1)));
    }

    @Operation(summary = "新增")
    @RequirePerm("monitor:target:add")
    @PostMapping
    public Result<Void> add(@RequestBody MonitorTarget target) {
        monitorTargetMapper.insert(target);
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("monitor:target:update")
    @PutMapping
    public Result<Void> update(@RequestBody MonitorTarget target) {
        monitorTargetMapper.updateById(target);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("monitor:target:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        monitorTargetMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "大盘数据")
    @RequirePerm("monitor:overview")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(monitorService.overview());
    }
}
