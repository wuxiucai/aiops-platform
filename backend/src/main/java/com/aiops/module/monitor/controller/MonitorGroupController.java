package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.entity.MonitorGroup;
import com.aiops.module.monitor.mapper.MonitorGroupMapper;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 监控分组
 */
@Tag(name = "监控分组")
@RestController
@RequestMapping("/api/monitor/group")
@RequiredArgsConstructor
public class MonitorGroupController {

    private final MonitorGroupMapper monitorGroupMapper;

    @Operation(summary = "全部分组")
    @RequirePerm("monitor:target:list")
    @GetMapping("/list")
    public Result<List<MonitorGroup>> list() {
        return Result.ok(monitorGroupMapper.selectList(null));
    }

    @Operation(summary = "新增")
    @RequirePerm("monitor:target:add")
    @PostMapping
    public Result<Void> add(@RequestBody MonitorGroup group) {
        monitorGroupMapper.insert(group);
        return Result.ok();
    }

    @Operation(summary = "修改")
    @RequirePerm("monitor:target:update")
    @PutMapping
    public Result<Void> update(@RequestBody MonitorGroup group) {
        monitorGroupMapper.updateById(group);
        return Result.ok();
    }

    @Operation(summary = "删除")
    @RequirePerm("monitor:target:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        monitorGroupMapper.deleteById(id);
        return Result.ok();
    }
}
