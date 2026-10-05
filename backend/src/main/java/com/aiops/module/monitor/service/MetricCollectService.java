package com.aiops.module.monitor.service;

import com.aiops.datasource.metric.ActuatorCollector;
import com.aiops.datasource.metric.MetricCollector;
import com.aiops.datasource.metric.OshiCollector;
import com.aiops.datasource.metric.PrometheusCollector;
import com.aiops.module.monitor.entity.CollectTask;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.CollectTaskMapper;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 指标采集服务：按 collect_task 驱动，OSHI/Actuator 两条通道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCollectService {

    private final CollectTaskMapper collectTaskMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final MetricDataMapper metricDataMapper;
    private final OshiCollector oshiCollector;
    private final ActuatorCollector actuatorCollector;
    private final PrometheusCollector prometheusCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 执行一条采集任务，返回写入条数 */
    public int runTask(CollectTask task) {
        long start = System.currentTimeMillis();
        MonitorTarget target = monitorTargetMapper.selectById(task.getTargetId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            return 0;
        }
        List<String> metricKeys = parseKeys(task.getMetricKeys());
        List<MetricCollector.MetricPoint> points = new ArrayList<>();
        if (prometheusCollector.supports(target.getTargetType())) {
            /* S4: prometheus 拉模式， 走 target_url */
            if (target.getTargetUrl() != null && !target.getTargetUrl().isBlank()) {
                points = prometheusCollector.collect(target.getId(), metricKeys, target.getTargetUrl());
            }
        } else if (oshiCollector.supports(target.getTargetType())) {
            points = oshiCollector.collect(target.getId(), metricKeys);
        } else if (actuatorCollector.supports(target.getTargetType())
                && target.getIp() != null && target.getPort() != null) {
            points = actuatorCollector.collect(target.getId(), metricKeys, target.getIp(), target.getPort());
        }
        // 落库
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (MetricCollector.MetricPoint p : points) {
            MetricData data = new MetricData();
            data.setTargetId(target.getId());
            data.setMetricKey(p.metricKey());
            data.setMetricValue(java.math.BigDecimal.valueOf(p.value()));
            data.setCollectTime(now);
            metricDataMapper.insert(data);
            inserted++;
        }
        // 更新任务状态
        CollectTask upd = new CollectTask();
        upd.setId(task.getId());
        upd.setLastRunTime(now);
        upd.setLastCostMs(System.currentTimeMillis() - start);
        upd.setFailCount(points.isEmpty() ? nvl(task.getFailCount()) + 1 : 0);
        collectTaskMapper.updateById(upd);
        return inserted;
    }

    /** 全部启用任务（供 Job 扫描） */
    public List<CollectTask> listEnabledTasks() {
        return collectTaskMapper.selectList(new LambdaQueryWrapper<CollectTask>()
                .eq(CollectTask::getStatus, 1));
    }

    private List<String> parseKeys(String json) {
        try {
            JsonNode arr = objectMapper.readTree(json);
            List<String> keys = new ArrayList<>();
            arr.forEach(n -> keys.add(n.asText()));
            return keys;
        } catch (Exception e) {
            log.error("[Metric] metric_keys 解析失败: {}", json);
            return List.of();
        }
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
