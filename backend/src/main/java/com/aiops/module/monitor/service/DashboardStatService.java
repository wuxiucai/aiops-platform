package com.aiops.module.monitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控大盘与自定义大盘 widget 的只读统计查询（自 DashboardStatController 下沉，SQL 未改动）。
 * 均为跨表聚合，用 JdbcTemplate 直写 SQL 比为每个查询建实体更直观。
 */
@Service
@RequiredArgsConstructor
public class DashboardStatService {

    private final JdbcTemplate jdbc;

    /** 单一大数字 + 可选 targetId 过滤
     * kind ∈ alert_pending_count / incident_open_count / log_anomaly_24h / llm_call_24h */
    public Map<String, Object> fullCount(
            String kind,
            Long targetId,
            Integer hours) {
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
                throw new com.aiops.common.BizException("不支持的统计类型: " + kind);
        }
        out.put("value", v);
        return out;
    }

    /** Top N 告警（按 trigger_count 排序）+ 可选 targetId 过滤 */
    public List<Map<String, Object>> topAlert(
            Integer limit,
            Integer hours,
            Long targetId) {
        String sql = "SELECT title, SUM(trigger_count) AS cnt, MAX(level) AS level, MAX(last_trigger_time) AS lastTime " +
                "FROM alert_record WHERE create_time > DATE_SUB(NOW(), INTERVAL ? HOUR) " +
                (targetId != null ? "AND target_id=? " : "") +
                "GROUP BY title ORDER BY cnt DESC LIMIT ?";
        Object[] args = targetId != null ? new Object[]{hours, targetId, limit} : new Object[]{hours, limit};
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows;
    }

    /** Top N 日志模板（按 last_window_count 排序）+ 可选 service 过滤 */
    public List<Map<String, Object>> topTemplate(
            Integer limit,
            String service) {
        String sql = "SELECT template_text AS template, total_count AS cnt, level, last_seen " +
                "FROM log_template " +
                (service != null && !service.isBlank() ? "WHERE service=? " : "") +
                "ORDER BY total_count DESC LIMIT ?";
        Object[] args = (service != null && !service.isBlank()) ? new Object[]{service, limit} : new Object[]{limit};
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows;
    }

    /** 日志模板可用 service 列表（下拉选） */
    public List<String> logServices() {
        List<String> list = jdbc.queryForList(
                "SELECT DISTINCT service FROM log_template WHERE service IS NOT NULL ORDER BY service",
                String.class);
        return list;
    }

    /** 事件分页 + 可选 targetId 过滤 */
    public Map<String, Object> incidentPage(
            Integer current,
            Integer size,
            String status,
            Long targetId) {
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
        return out;
    }

    /* ================== 监控大盘全局概览 ================== */

    /** 高 CPU 判定阈值（%） */
    private static final double CPU_HOT_THRESHOLD = 80.0;
    /** 高内存判定阈值（%） */
    private static final double MEM_HIGH_THRESHOLD = 85.0;

    /**
     * 监控大盘「整体概览」聚合接口：
     * 对象构成 / Agent 在线、活跃 CRITICAL 告警机器数、高 CPU / 高内存机器数（含最高负载目标）、
     * 开放事件、24h 告警、全部 host 近 1h CPU/内存均值。
     * 一次请求带齐，供大盘顶部概览卡片使用。
     */
    public Map<String, Object> overview() {
        Map<String, Object> out = new HashMap<>();

        // ---- 监控对象构成 + Agent 在线 ----
        Long hostCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_target WHERE deleted=0 AND target_type='host'", Long.class);
        Long serviceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_target WHERE deleted=0 AND target_type='service'", Long.class);
        Long agentOnline = jdbc.queryForObject(
                // 心跳实际写在 monitor_agent 表（monitor_target.last_heartbeat 从未被回写），
                // 在线口径与 AgentController 一致：agent 心跳在 2 分钟内
                "SELECT COUNT(DISTINCT a.target_id) FROM monitor_agent a " +
                        "JOIN monitor_target t ON t.id = a.target_id AND t.deleted = 0 " +
                        "WHERE a.last_heartbeat IS NOT NULL AND a.last_heartbeat >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)",
                Long.class);
        long targetTotal = nz(hostCount) + nz(serviceCount);
        out.put("targetTotal", targetTotal);
        out.put("hostCount", nz(hostCount));
        out.put("serviceCount", nz(serviceCount));
        out.put("agentOnline", nz(agentOnline));
        out.put("agentOffline", Math.max(0, targetTotal - nz(agentOnline)));

        // ---- 告警 / 事件 ----
        out.put("pendingAlerts", nz(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_record WHERE status='pending'", Long.class)));
        // 活跃 CRITICAL 告警涉及的机器数（去重 target）
        out.put("criticalAlertTargets", nz(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT target_id) FROM alert_record WHERE status='pending' AND level='CRITICAL'",
                Long.class)));
        out.put("alert24h", nz(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_record WHERE create_time > DATE_SUB(NOW(), INTERVAL 24 HOUR)",
                Long.class)));
        out.put("openIncidents", nz(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_incident WHERE status='open'", Long.class)));

        // ---- 高 CPU / 高内存机器：取每个 target 最近 15min 内最新一条采样 ----
        String latestSql = """
                SELECT t.name AS name, m.metric_value AS v
                FROM metric_data m
                JOIN monitor_target t ON t.id = m.target_id AND t.deleted = 0
                JOIN (SELECT target_id, MAX(collect_time) AS mt
                        FROM metric_data
                       WHERE metric_key = ? AND collect_time >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)
                       GROUP BY target_id) l
                  ON l.target_id = m.target_id AND l.mt = m.collect_time
                WHERE m.metric_key = ?
                """;
        fillHighLoad(out, "cpu", latestSql, "cpu.usage", CPU_HOT_THRESHOLD);
        fillHighLoad(out, "mem", latestSql, "mem.usage", MEM_HIGH_THRESHOLD);

        out.put("cpuThreshold", CPU_HOT_THRESHOLD);
        out.put("memThreshold", MEM_HIGH_THRESHOLD);

        // ---- 全部 host 近 1h CPU / 内存均值 ----
        String avgSql = """
                SELECT AVG(m.metric_value) FROM metric_data m
                WHERE m.metric_key = ? AND m.collect_time >= DATE_SUB(NOW(), INTERVAL 60 MINUTE)
                  AND EXISTS (SELECT 1 FROM monitor_target t
                               WHERE t.id = m.target_id AND t.deleted = 0 AND t.target_type = 'host')
                """;
        Double avgCpu = jdbc.queryForObject(avgSql, Double.class, "cpu.usage");
        Double avgMem = jdbc.queryForObject(avgSql, Double.class, "mem.usage");
        out.put("avgCpu", avgCpu == null ? null : round1(avgCpu));
        out.put("avgMem", avgMem == null ? null : round1(avgMem));

        return out;
    }

    /** 计算 high{kind}Count 与 top{kind}Name/Value（最新采样里超过阈值的机器数 + 最高的一台） */
    private void fillHighLoad(Map<String, Object> out, String kind, String latestSql,
                              String metricKey, double threshold) {
        List<Map<String, Object>> rows = jdbc.queryForList(latestSql, metricKey, metricKey);
        int high = 0;
        String topName = null;
        Double topValue = null;
        for (Map<String, Object> r : rows) {
            Object raw = r.get("v");
            if (raw == null) continue;
            double v = ((Number) raw).doubleValue();
            if (v >= threshold) high++;
            if (topValue == null || v > topValue) {
                topValue = v;
                topName = (String) r.get("name");
            }
        }
        out.put("high" + kind + "Count", high);
        out.put("top" + kind + "Name", topName);
        out.put("top" + kind + "Value", topValue == null ? null : round1(topValue));
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    /**
     * 目标健康矩阵：每个监控对象一行，返回最新采样（15min 内）的 CPU/内存、
     * 活跃告警数（含 CRITICAL 计数）、Agent 是否在线（2min 心跳，与 overview 同口径）。
     * 颜色分档由前端计算：红 = CRITICAL 告警或任一指标 >=90；橙 = 有活跃告警或 >=80/85；绿 = 正常；灰 = 无数据。
     */
    public List<Map<String, Object>> healthMatrix() {
        List<Map<String, Object>> targets = jdbc.queryForList(
                "SELECT id, name, target_type AS targetType FROM monitor_target WHERE deleted=0 ORDER BY id");

        // 每 target 最新一条 cpu/mem 采样（15min 窗口内）
        String latestSql = """
                SELECT m.target_id AS targetId, m.metric_key AS metricKey, m.metric_value AS v
                FROM metric_data m
                JOIN (SELECT target_id, metric_key, MAX(collect_time) AS mt
                        FROM metric_data
                       WHERE metric_key IN ('cpu.usage','mem.usage')
                         AND collect_time >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)
                       GROUP BY target_id, metric_key) l
                  ON l.target_id = m.target_id AND l.metric_key = m.metric_key AND l.mt = m.collect_time
                """;
        Map<Long, Double> cpuMap = new HashMap<>();
        Map<Long, Double> memMap = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(latestSql)) {
            if (r.get("v") == null || r.get("targetId") == null) continue;
            long tid = ((Number) r.get("targetId")).longValue();
            double v = ((Number) r.get("v")).doubleValue();
            if ("cpu.usage".equals(r.get("metricKey"))) cpuMap.put(tid, v);
            else if ("mem.usage".equals(r.get("metricKey"))) memMap.put(tid, v);
        }

        // 活跃告警按 target 聚合
        Map<Long, long[]> alertMap = new HashMap<>(); // [pendingCnt, criticalCnt]
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT target_id AS tid, COUNT(*) AS cnt, " +
                        "SUM(CASE WHEN level='CRITICAL' THEN 1 ELSE 0 END) AS criticalCnt " +
                        "FROM alert_record WHERE status='pending' AND target_id IS NOT NULL GROUP BY target_id")) {
            if (r.get("tid") == null) continue;
            long tid = ((Number) r.get("tid")).longValue();
            long cnt = r.get("cnt") == null ? 0 : ((Number) r.get("cnt")).longValue();
            long crit = r.get("criticalCnt") == null ? 0 : ((Number) r.get("criticalCnt")).longValue();
            alertMap.put(tid, new long[]{cnt, crit});
        }

        // Agent 在线（2min 心跳，存于 monitor_agent 表）
        java.util.Set<Long> onlineIds = new java.util.HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT DISTINCT a.target_id AS tid FROM monitor_agent a " +
                        "JOIN monitor_target t ON t.id = a.target_id AND t.deleted = 0 " +
                        "WHERE a.last_heartbeat IS NOT NULL AND a.last_heartbeat >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)")) {
            onlineIds.add(((Number) r.get("tid")).longValue());
        }

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> t : targets) {
            long tid = ((Number) t.get("id")).longValue();
            Map<String, Object> row = new HashMap<>();
            row.put("targetId", tid);
            row.put("name", t.get("name"));
            row.put("targetType", t.get("targetType"));
            Double cpu = cpuMap.get(tid);
            Double mem = memMap.get(tid);
            row.put("cpu", cpu == null ? null : round1(cpu));
            row.put("mem", mem == null ? null : round1(mem));
            long[] al = alertMap.getOrDefault(tid, new long[]{0, 0});
            row.put("pendingAlerts", al[0]);
            row.put("criticalAlerts", al[1]);
            row.put("agentOnline", onlineIds.contains(tid));
            out.add(row);
        }
        return out;
    }
}
