package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.entity.DashboardGroup;
import com.aiops.module.monitor.service.DashboardGroupService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * S3 仪表盘分组管理（user-scoped 树）。实现见 {@link DashboardGroupService}。
 */
@Tag(name = "仪表盘分组")
@RestController
@RequestMapping("/api/dashboard/group")
@RequiredArgsConstructor
public class DashboardGroupController {

    private final DashboardGroupService dashboardGroupService;

    @Operation(summary = "树形分组（含未归类模板计数）")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/tree")
    public Result<Map<String, Object>> tree() {
        return Result.ok(dashboardGroupService.tree());
    }

    @Operation(summary = "新建分组")
    @RequirePerm("monitor:dashboard:update")
    @PostMapping
    public Result<DashboardGroup> add(@RequestBody DashboardGroup body) {
        return Result.ok(dashboardGroupService.add(body));
    }

    @Operation(summary = "重命名/移动分组")
    @RequirePerm("monitor:dashboard:update")
    @PutMapping
    public Result<Void> update(@RequestBody DashboardGroup body) {
        dashboardGroupService.update(body);
        return Result.ok();
    }

    @Operation(summary = "删除分组（其下模板移入未归类，子分组上挂）")
    @RequirePerm("monitor:dashboard:update")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dashboardGroupService.delete(id);
        return Result.ok();
    }
}
