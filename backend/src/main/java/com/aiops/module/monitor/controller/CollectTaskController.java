package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.entity.CollectTask;
import com.aiops.module.monitor.mapper.CollectTaskMapper;
import com.aiops.module.monitor.service.MetricCollectService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 采集任务管理
 */
@Tag(name = "采集任务")
@RestController
@RequestMapping("/api/monitor/collect/task")
@RequiredArgsConstructor
public class CollectTaskController {

    private final CollectTaskMapper collectTaskMapper;
    private final MetricCollectService metricCollectService;

    @Operation(summary = "分页列表")
    @RequirePerm("monitor:collect:list")
    @GetMapping("/page")
    public Result<Page<CollectTask>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size) {
        return Result.ok(collectTaskMapper.selectPage(new Page<>(current, size), null));
    }

    @Operation(summary = "新增")
    @RequirePerm("monitor:collect:add")
    @PostMapping
    public Result<Void> add(@RequestBody CollectTask task) {
        collectTaskMapper.insert(task);
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("monitor:collect:update")
    @PutMapping
    public Result<Void> update(@RequestBody CollectTask task) {
        collectTaskMapper.updateById(task);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("monitor:collect:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        collectTaskMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "立即执行一次")
    @RequirePerm("monitor:collect:update")
    @PostMapping("/{id}/run")
    public Result<Integer> runOnce(@PathVariable Long id) {
        CollectTask task = collectTaskMapper.selectById(id);
        return Result.ok(metricCollectService.runTask(task));
    }
}
