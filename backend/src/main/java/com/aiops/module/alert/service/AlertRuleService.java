package com.aiops.module.alert.service;

import com.aiops.module.alert.entity.AlertRule;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警规则业务接口
 */
public interface AlertRuleService {

    /** 训练基线（写/更新 baseline_model） */
    Map<String, Object> trainBaseline(Long ruleId);

    /** 基线图（按日期返回 24h 上/下界与实际值） */
    Map<String, Object> baselineChart(Long ruleId, Long targetId, String date);

    /** 干跑回放：用 static/baseline 逻辑在历史数据上模拟触发 */
    Map<String, Object> dryRun(Long ruleId, LocalDateTime startTime, LocalDateTime endTime);

    /** 删除规则连带清理 baseline_model */
    void deleteRule(Long id);

    /** 启用/禁用切换 */
    void toggle(Long id, Integer enabled);

    AlertRule getById(Long id);
}
