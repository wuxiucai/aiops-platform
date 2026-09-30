package com.aiops.module.llm.service.fallback;

import com.aiops.module.log.mapper.LogTemplateMapper;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M5-7：三场景 LLM 失败时的兜底（规则结果，避免 500）。
 * <p>
 * 审查方约束：fallback 结果必须带 "isLlmFallback": true，前端据此展示"系统降级提示"。
 * 本类不调用 LLM，纯由 DB 现成数据拼 JSON。
 */
@Component
public class ScenarioFallbackProvider {

    private final LogTemplateMapper logTemplateMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertIncidentMapper alertIncidentMapper;

    public ScenarioFallbackProvider(LogTemplateMapper logTemplateMapper,
                                    AlertRecordMapper alertRecordMapper,
                                    AlertIncidentMapper alertIncidentMapper) {
        this.logTemplateMapper = logTemplateMapper;
        this.alertRecordMapper = alertRecordMapper;
        this.alertIncidentMapper = alertIncidentMapper;
    }

    /** 统一入口：按 scene 返回 fallback JSON map（必须含 isLlmFallback=true）。 */
    public Map<String, Object> fallback(String sceneCode, Long refId) {
        Map<String, Object> base = switch (sceneCode) {
            case "log_explain" -> logExplainFallback(refId);
            case "alert_explain" -> alertExplainFallback(refId);
            case "root_cause" -> rootCauseFallback(refId);
            default -> Map.of("reason", "unknown scene: " + sceneCode,
                              "confidence", 0.0);
        };
        // 注入统一标志
        java.util.Map<String, Object> merged = new java.util.HashMap<>(base);
        merged.put("isLlmFallback", true);
        return merged;
    }

    /* ============= 1) log_explain ============= */

    private Map<String, Object> logExplainFallback(Long refId) {
        LogTemplate t = refId == null ? null : logTemplateMapper.selectById(refId);
        if (t == null) {
            return Map.of(
                    "summary", "未找到对应模板（id=" + refId + "），请人工查看样本日志",
                    "likelyCause", "未知（无模板数据）",
                    "suggestion", "通过日志检索页搜索最近一次出现的 error 日志",
                    "confidence", 0.0);
        }
        long count = t.getTotalCount() == null ? 0 : t.getTotalCount();
        return Map.of(
                "summary", "日志模板「" + abbrev(t.getTemplateText(), 60) + "」近 1h 内已记录 " + count + " 次",
                "likelyCause", "请人工查看样本日志相关上下文（模板：" + abbrev(t.getTemplateText(), 60) + "）",
                "suggestion", "检查该服务（" + (t.getService() == null ? "-" : t.getService()) + "）近期发布记录；如有 rollback 候选可优先评估",
                "confidence", 0.0);
    }

    /* ============= 2) alert_explain ============= */

    private Map<String, Object> alertExplainFallback(Long refId) {
        AlertRecord a = refId == null ? null : alertRecordMapper.selectById(refId);
        if (a == null) {
            return Map.of(
                    "summary", "告警不存在（id=" + refId + "）",
                    "severityAssessment", "高",
                    "possibleCauses", List.of("告警已删除或未及时写入"),
                    "suggestions", List.of("在告警中心查看是否存在同规则的活跃告警"),
                    "confidence", 0.0);
        }
        double threshold = a.getThresholdValue() == null ? 0 : a.getThresholdValue().doubleValue();
        double val = a.getTriggerValue() == null ? 0 : a.getTriggerValue().doubleValue();
        String metric = a.getMetricKey() == null ? "(metric)" : a.getMetricKey();
        String over = threshold > 0
                ? String.valueOf(Math.round((val / threshold - 1) * 100)) + "%"
                : "(超阈值)";
        return Map.of(
                "summary", "指标 " + metric + " 触发阈值 = " + val + " > " + threshold + "，超出 " + over,
                "severityAssessment", a.getLevel() == null ? "高" : a.getLevel(),
                "possibleCauses", List.of(
                        "瞬时压力/请求飙升（结合 " + metric + " 趋势判断）",
                        "配置/资源异常（该 target 上近 1h 是否有变更）"
                ),
                "suggestions", List.of(
                        "在监控 → 指标查看 " + metric + " 最近 30 分钟曲线",
                        "若是 jvm.heap.usage，可立即检查 GC/Full GC 频次",
                        "若是 cpu.usage，排查是否存在死循环或死锁"
                ),
                "confidence", 0.0);
    }

    /* ============= 3) root_cause ============= */

    private Map<String, Object> rootCauseFallback(Long refId) {
        AlertIncident inc = refId == null ? null : alertIncidentMapper.selectById(refId);
        if (inc == null) {
            return Map.of(
                    "rootCauses", List.of(Map.of("target", "(unknown)", "metric", "(unknown)",
                            "reason", "事件不存在（id=" + refId + "）", "confidence", 0.0,
                            "evidence", List.of("无"))),
                    "timeline", List.of(),
                    "confidence", 0.0);
        }
        // 从并发告警里按"时间最早 + 最常见的 metric 前缀"选一个规则候评
        List<AlertRecord> alerts = alertRecordMapper.selectList(
                new LambdaQueryWrapper<AlertRecord>()
                        .eq(AlertRecord::getIncidentId, inc.getId())
                        .orderByAsc(AlertRecord::getFirstTriggerTime)
                        .last("LIMIT 5"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (AlertRecord a : alerts) {
            candidates.add(Map.of(
                    "target", "target_id=" + a.getTargetId(),
                    "metric", a.getMetricKey() == null ? "(metric)" : a.getMetricKey(),
                    "reason", "规则候选：" + (a.getMetricKey() == null ? "未知指标" : a.getMetricKey())
                            + " 首次触发时间最早，且 trigger_value=" +
                            (a.getTriggerValue() == null ? "-" : a.getTriggerValue()),
                    "confidence", 0.0,
                    "evidence", List.of("首次触发于 " + (a.getFirstTriggerTime() == null ? "-" : a.getFirstTriggerTime()))));
        }
        return Map.of(
                "primaryCause", candidates.isEmpty() ? Map.of("target", "-", "metric", "-",
                        "reason", "关联告警为空", "confidence", 0.0) : candidates.get(0),
                "otherCandidates", candidates.size() > 1 ? candidates.subList(1, candidates.size()) : List.of(),
                "timeline", List.of("(fallback 无时间线分析)"),
                "suggestions", List.of("先恢复 LLM 服务、再重跑 root_cause"),
                "confidence", 0.0);
    }

    /* ============= 辅助 =================== */

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
