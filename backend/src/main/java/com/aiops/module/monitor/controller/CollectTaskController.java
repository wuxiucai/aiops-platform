package com.aiops.module.monitor.controller;

import com.aiops.common.BizException;
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

import java.util.Set;

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

    /**
     * 采集间隔白名单：1s / 5s / 15s / 30s / 60s / 300s。
     * 1s 仅用于临时调试（每秒打 actuator + 写库压力翻倍），生产建议 ≥15s。
     */
    private static final Set<Integer> ALLOWED_INTERVALS = Set.of(1, 5, 15, 30, 60, 300);

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
        validateInterval(task);
        collectTaskMapper.insert(task);
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("monitor:collect:update")
    @PutMapping
    public Result<Void> update(@RequestBody CollectTask task) {
        validateInterval(task);
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

    /** interval_sec 必须在白名单内，否则拒绝（防止用户配 0 / 负值 / 极端值） */
    private void validateInterval(CollectTask task) {
        // 仅当请求里带了 intervalSec 才校验；为 null 时沿用 DB 原值
        if (task.getIntervalSec() == null) return;
        if (!ALLOWED_INTERVALS.contains(task.getIntervalSec())) {
            throw new BizException("采集间隔仅允许 " + ALLOWED_INTERVALS + " 秒，收到 " + task.getIntervalSec());
        }
    }
}
