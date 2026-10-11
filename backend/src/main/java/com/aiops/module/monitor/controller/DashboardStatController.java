package com.aiops.module.monitor.controller;

import com.aiops.common.Result;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 统计接口（支持 targetId / service 可选过滤）
 * <p>在 widget 上用于：
 *  stat_card    → /full-count?kind=...&targetId=?     单一大数字
 *  top_alert    → /top-alert?targetId=?               Top 告警列表
 *  top_template → /top-template?service=?             Top 日志模板
 *  incident_list→ /incident-page?targetId=?           事件分页
 */
@Tag(name = "DashboardStat")
@RestController
@RequestMapping("/api/dashboard-stat")
@RequiredArgsConstructor
public class DashboardStatController {

    private final JdbcTemplate jdbc;

    /** 单一大数字 + 可选 targetId 过滤
     * kind ∈ alert_pending_count / incident_open_count / log_anomaly_24h / llm_call_24h */
    @Operation(summary = "stat_card 单值")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/full-count")
    public Result<Map<String, Object>> fullCount(
            @RequestParam String kind,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false, defaultValue = "24") Integer hours) {
        Map<String, Object> out = new HashMap<>();
        long v = 0;
        switch (kind) {
            case "alert_pending_count":
                v = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM alert_record WHERE status='pending'" +
                                (targetId != null ? " AND target_id=?" : ""),
                        Long.class,
                        targetId != null ? new Object[]{targetId} : new Object[]{});
                break;
            case "incident_open_count":
                v = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM alert_incident WHERE status='open'" +
                                (targetId != null ? " AND primary_target_id=?" : ""),
                        Long.class,
                        targetId != null ? new Object[]{targetId} : new Object[]{});
                break;
            case "log_anomaly_24h":
                v = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM log_anomaly WHERE create_time > DATE_SUB(NOW(), INTERVAL ? HOUR)" +
                                (targetId != null ? " AND datasource_id=?" : ""),
                        Long.class,
                        targetId != null ? new Object[]{hours, targetId} : new Object[]{hours});
                break;
            case "llm_call_24h":
                v = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM llm_call_log WHERE create_time > DATE_SUB(NOW(), INTERVAL ? HOUR)",
                        Long.class, hours);
                break;
            default:
                throw new IllegalArgumentException("unknown kind: " + kind);
        }
        out.put("value", v);
        return Result.ok(out);
    }

    /** Top N 告警（按 trigger_count 排序）+ 可选 targetId 过滤 */
    @Operation(summary = "Top 告警")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/top-alert")
    public Result<List<Map<String, Object>>> topAlert(
            @RequestParam(defaultValue = "5") Integer limit,
            @RequestParam(required = false, defaultValue = "24") Integer hours,
            @RequestParam(required = false) Long targetId) {
        String sql = "SELECT title, SUM(trigger_count) AS cnt, MAX(level) AS level, MAX(last_trigger_time) AS lastTime " +
                "FROM alert_record WHERE create_time > DATE_SUB(NOW(), INTERVAL ? HOUR) " +
                (targetId != null ? "AND target_id=? " : "") +
                "GROUP BY title ORDER BY cnt DESC LIMIT ?";
        Object[] args = targetId != null ? new Object[]{hours, targetId, limit} : new Object[]{hours, limit};
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return Result.ok(rows);
    }

    /** Top N 日志模板（按 last_window_count 排序）+ 可选 service 过滤 */
    @Operation(summary = "Top 日志模板")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/top-template")
    public Result<List<Map<String, Object>>> topTemplate(
            @RequestParam(defaultValue = "5") Integer limit,
            @RequestParam(required = false) String service) {
        String sql = "SELECT template_text AS template, total_count AS cnt, level, last_seen " +
                "FROM log_template " +
                (service != null && !service.isBlank() ? "WHERE service=? " : "") +
                "ORDER BY total_count DESC LIMIT ?";
        Object[] args = (service != null && !service.isBlank()) ? new Object[]{service, limit} : new Object[]{limit};
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return Result.ok(rows);
    }

    /** 日志模板可用 service 列表（下拉选） */
    @Operation(summary = "日志 service 列表")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/log-services")
    public Result<List<String>> logServices() {
        List<String> list = jdbc.queryForList(
                "SELECT DISTINCT service FROM log_template WHERE service IS NOT NULL ORDER BY service",
                String.class);
        return Result.ok(list);
    }

    /** 事件分页 + 可选 targetId 过滤 */
    @Operation(summary = "事件分页")
    @RequirePerm("monitor:dashboard:list")
    @GetMapping("/incident-page")
    public Result<Map<String, Object>> incidentPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false, defaultValue = "open") String status,
            @RequestParam(required = false) Long targetId) {
        String where = " WHERE status=?" + (targetId != null ? " AND primary_target_id=?" : "");
        Object[] args = targetId != null
                ? new Object[]{status, targetId, (current - 1) * size, size}
                : new Object[]{status, (current - 1) * size, size};
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT id, incident_no, title, level, status, alert_count, start_time, end_time " +
                        "FROM alert_incident" + where + " ORDER BY start_time DESC LIMIT ?,?", args);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_incident" + where,
                Long.class,
                targetId != null ? new Object[]{status, targetId} : new Object[]{status});
        Map<String, Object> out = new HashMap<>();
        out.put("records", records);
        out.put("total", total);
        return Result.ok(out);
    }
}
