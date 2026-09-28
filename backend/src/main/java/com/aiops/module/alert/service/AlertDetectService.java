package com.aiops.module.alert.service;

/**
 * 告警检测流水线（§5.5.2）：对一条规则跑 触发→持续→去重→静默→抑制→关事件→通知
 */
public interface AlertDetectService {

    /** 处理一条规则；返回是否触发新告警（或合并更新已有） */
    boolean processRule(Long ruleId);
}
