package com.aiops.datasource.metric;

import java.util.List;

/**
 * 指标采集器接口（§2.2 datasource/metric）
 */
public interface MetricCollector {

    /**
     * 采集指定指标，返回 (metricKey, value) 对
     *
     * @param targetId   监控目标 id
     * @param metricKeys 要采集的指标 key 列表
     */
    List<MetricPoint> collect(Long targetId, List<String> metricKeys);

    /** 是否支持该目标类型 */
    boolean supports(String targetType);

    record MetricPoint(String metricKey, double value) {
    }
}
