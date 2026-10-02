package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.entity.DashboardTemplate;
import com.aiops.module.monitor.service.DashboardService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 自定义大盘模板 CRUD。 */
@Tag(name = "自定义大盘")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "模板列表（含 widgets)")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/templates")
    public Result<List<Map<String, Object>>> templates() {
        return Result.ok(dashboardService.listAll());
    }

    @Operation(summary = "模板详情")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/template/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(dashboardService.detail(id));
    }

    @Operation(summary = "新建模板")
    @RequirePerm("monitor:dashboard:update")
    @PostMapping("/template")
    public Result<DashboardTemplate> create(@RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.create(body));
    }

    @Operation(summary = "保存模板（layout + widgets)")
    @RequirePerm("monitor:dashboard:update")
    @PutMapping("/template/{id}")
    public Result<DashboardTemplate> save(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(dashboardService.save(id, body));
    }

    @Operation(summary = "删除模板")
    @RequirePerm("monitor:dashboard:update")
    @DeleteMapping("/template/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dashboardService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "设为默认")
    @RequirePerm("monitor:dashboard:update")
    @PutMapping("/template/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        dashboardService.setDefault(id);
        return Result.ok();
    }
}
