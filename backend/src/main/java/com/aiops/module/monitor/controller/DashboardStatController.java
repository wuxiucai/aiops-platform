package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.module.monitor.service.DashboardStatService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 监控大盘与自定义大盘统计接口（支持 targetId / service 可选过滤）。
 * <p>widget 映射：
 *  stat_card    → /full-count?kind=...&targetId=?
 *  top_alert    → /top-alert?targetId=?
 *  top_template → /top-template?service=?
 *  incident_list→ /incident-page?targetId=?
 * 查询实现见 {@link DashboardStatService}。
 */
@Tag(name = "DashboardStat")
@RestController
@RequestMapping("/api/dashboard-stat")
@RequiredArgsConstructor
public class DashboardStatController {

    private final DashboardStatService dashboardStatService;

    /** kind ∈ alert_pending_count / incident_open_count / log_anomaly_24h / llm_call_24h */
    @Operation(summary = "stat_card 单值")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/full-count")
    public Result<Map<String, Object>> fullCount(@RequestParam String kind,
                                                 @RequestParam(required = false) Long targetId,
                                                 @RequestParam(required = false, defaultValue = "24") Integer hours) {
        return Result.ok(dashboardStatService.fullCount(kind, targetId, hours));
    }

    @Operation(summary = "Top 告警")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/top-alert")
    public Result<List<Map<String, Object>>> topAlert(@RequestParam(defaultValue = "5") Integer limit,
                                                      @RequestParam(required = false, defaultValue = "24") Integer hours,
                                                      @RequestParam(required = false) Long targetId) {
        return Result.ok(dashboardStatService.topAlert(limit, hours, targetId));
    }

    @Operation(summary = "Top 日志模板")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/top-template")
    public Result<List<Map<String, Object>>> topTemplate(@RequestParam(defaultValue = "5") Integer limit,
                                                         @RequestParam(required = false) String service) {
        return Result.ok(dashboardStatService.topTemplate(limit, service));
    }

    @Operation(summary = "日志 service 列表")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/log-services")
    public Result<List<String>> logServices() {
        return Result.ok(dashboardStatService.logServices());
    }

    @Operation(summary = "事件分页")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/incident-page")
    public Result<Map<String, Object>> incidentPage(@RequestParam(defaultValue = "1") Integer current,
                                                    @RequestParam(defaultValue = "10") Integer size,
                                                    @RequestParam(required = false, defaultValue = "open") String status,
                                                    @RequestParam(required = false) Long targetId) {
        return Result.ok(dashboardStatService.incidentPage(current, size, status, targetId));
    }

    @Operation(summary = "监控大盘全局概览")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(dashboardStatService.overview());
    }

    @Operation(summary = "目标健康矩阵")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/health-matrix")
    public Result<List<Map<String, Object>>> healthMatrix() {
        return Result.ok(dashboardStatService.healthMatrix());
    }
}
