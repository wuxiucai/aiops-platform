package com.aiops.module.stat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析：平台效果指标 + LLM 成本（自 StatController 下沉，SQL 未改动）。
 * 数据来源 alert_record / alert_incident / log_template / llm_call_log，全部只读聚合。
 */
@Service
@RequiredArgsConstructor
public class StatService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 效果指标 - 综合一页返回。
     * - 告警： 总数 / pending / claimed / resolved, MTTA, 按 level 分布, 按 day 趋势
     * - 事件： open/mitigated/resolved, MTTR, 平均关联告警数
     * - 日志模板： 模板数 / 日志总条数 / 压缩率, top10 模板
     * - 误报率： 状态为 false_positive（告警页「标记误报」），或备注含 '误报'/'false'
     */
    public Map<String, Object> effect(int days) {
        Map<String, Object> r = new HashMap<>();
        r.put("days", days);

        /* ========== 告警指标 ========== */
        Map<String, Object> alerts = jdbcTemplate.queryForMap("""
                SELECT
                  COUNT(*) AS total,
                  SUM(CASE WHEN status='pending'  THEN 1 ELSE 0 END) AS pending,
                  SUM(CASE WHEN status='processing' THEN 1 ELSE 0 END) AS claimed,
                  SUM(CASE WHEN status='resolved' THEN 1 ELSE 0 END) AS resolved,
                  SUM(CASE WHEN status='false_positive' OR handle_remark LIKE '%误报%' OR handle_remark LIKE '%false%'
                           THEN 1 ELSE 0 END) AS falsePositives,
                  AVG(CASE WHEN claimed_time IS NOT NULL AND first_trigger_time IS NOT NULL
                           THEN TIMESTAMPDIFF(SECOND, first_trigger_time, claimed_time) END) AS mtta_sec,
                  AVG(CASE WHEN resolved_time IS NOT NULL AND first_trigger_time IS NOT NULL
                           THEN TIMESTAMPDIFF(SECOND, first_trigger_time, resolved_time) END) AS mttr_alert_sec
                FROM alert_record
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                """, days);
        r.put("alert", alerts);

        /* 按级别分布 */
        List<Map<String, Object>> byLevel = jdbcTemplate.queryForList("""
                SELECT level, COUNT(*) AS cnt
                FROM alert_record
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY level ORDER BY cnt DESC
                """, days);
        r.put("alertByLevel", byLevel);

        /* 按天趋势 */
        List<Map<String, Object>> byDay = jdbcTemplate.queryForList("""
                SELECT DATE(create_time) AS d, COUNT(*) AS cnt,
                       SUM(CASE WHEN status='resolved' THEN 1 ELSE 0 END) AS resolved
                FROM alert_record
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY DATE(create_time) ORDER BY d
                """, days);
        r.put("alertByDay", byDay);

        /* ========== 事件指标 ========== */
        Map<String, Object> incidents = jdbcTemplate.queryForMap("""
                SELECT
                  COUNT(*) AS total,
                  SUM(CASE WHEN status='open'      THEN 1 ELSE 0 END) AS open,
                  SUM(CASE WHEN status='mitigated' THEN 1 ELSE 0 END) AS mitigated,
                  SUM(CASE WHEN status='resolved'  THEN 1 ELSE 0 END) AS resolved,
                  AVG(CASE WHEN duration_sec IS NOT NULL THEN duration_sec END) AS mttr_sec,
                  AVG(alert_count) AS avg_alert_per_incident
                FROM alert_incident
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY) AND deleted=0
                """, days);
        r.put("incident", incidents);

        /* ========== 日志模板 - 压缩率 ========== */
        Map<String, Object> tpl = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS tpl_count, COALESCE(SUM(total_count),0) AS hit_total
                FROM log_template
                """);
        r.put("template", tpl);

        /* top10 模板 */
        List<Map<String, Object>> topTpl = jdbcTemplate.queryForList("""
                SELECT id, template_text, total_count, service
                FROM log_template ORDER BY total_count DESC LIMIT 10
                """);
        r.put("topTemplates", topTpl);

        /* ========== 误报率（分母=已处理告警） ========== */
        Object total = alerts.get("total");
        Object fp = alerts.get("falsePositives");
        double fpr = 0.0;
        if (total instanceof Number t && fp instanceof Number f && t.longValue() > 0) {
            fpr = f.doubleValue() / t.doubleValue();
        }
        r.put("falsePositiveRate", fpr);

        return r;
    }

    /**
     * LLM 成本统计：
     * - 总调用次数 / 总 token / 成功失败比例
     * - 按 scene_code 分组次数 + token
     * - 按 provider 分组
     * - 按天趋势 (近 N 天)
     * - 慢调用 top10
     */
    public Map<String, Object> llmCost(int days) {
        Map<String, Object> r = new HashMap<>();
        r.put("days", days);

        // llm_call_log.status 实际值是 'success' / 'fail'（见 AiScenarioService /
        // IncidentReportService / Nl2QueryService），而不是 'ok' /
        // 'fail'。过滤条件写错会导致 ok_calls 恒为 0、成本面板成功率 0%。
        Map<String, Object> overview = jdbcTemplate.queryForMap("""
                SELECT
                  COUNT(*) AS total_calls,
                  SUM(CASE WHEN status='success' THEN 1 ELSE 0 END) AS ok_calls,
                  SUM(CASE WHEN status<>'success' OR status IS NULL THEN 1 ELSE 0 END) AS failed_calls,
                  COALESCE(SUM(prompt_tokens),0)     AS prompt_tokens,
                  COALESCE(SUM(completion_tokens),0) AS completion_tokens,
                  COALESCE(SUM(total_tokens),0)      AS total_tokens,
                  AVG(latency_ms) AS avg_latency_ms,
                  MAX(latency_ms) AS max_latency_ms
                FROM llm_call_log
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                """, days);
        r.put("overview", overview);

        List<Map<String, Object>> byScene = jdbcTemplate.queryForList("""
                SELECT scene_code, COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens),0) AS total_tokens,
                       AVG(latency_ms) AS avg_latency_ms
                FROM llm_call_log
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY scene_code ORDER BY total_tokens DESC
                """, days);
        r.put("byScene", byScene);

        List<Map<String, Object>> byProvider = jdbcTemplate.queryForList("""
                SELECT provider_id, COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens),0) AS total_tokens
                FROM llm_call_log
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY provider_id ORDER BY total_tokens DESC
                """, days);
        r.put("byProvider", byProvider);

        List<Map<String, Object>> byDay = jdbcTemplate.queryForList("""
                SELECT DATE(create_time) AS d, COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens),0) AS total_tokens
                FROM llm_call_log
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                GROUP BY DATE(create_time) ORDER BY d
                """, days);
        r.put("byDay", byDay);

        List<Map<String, Object>> slowTop = jdbcTemplate.queryForList("""
                SELECT id, scene_code, provider_id, total_tokens, latency_ms, status, create_time
                FROM llm_call_log
                WHERE create_time >= DATE_SUB(NOW(), INTERVAL ? DAY) AND latency_ms IS NOT NULL
                ORDER BY latency_ms DESC LIMIT 10
                """, days);
        r.put("slowTop10", slowTop);

        return r;
    }
}
