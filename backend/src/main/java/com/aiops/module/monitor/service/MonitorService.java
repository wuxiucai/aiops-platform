package com.aiops.module.monitor.service;

import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控服务：大盘聚合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitorTargetMapper monitorTargetMapper;
    private final MetricDataMapper metricDataMapper;

    /** GET /api/monitor/overview：{targetCount, alert24h, incidentOpen, avgCpu, avgMem, topAlerts} */
    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        result.put("targetCount", monitorTargetMapper.selectCount(null));

        // 最近 1h CPU/内存均值（取本机 host 目标）
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<MonitorTarget> hosts = monitorTargetMapper.selectList(
                new LambdaQueryWrapper<MonitorTarget>().eq(MonitorTarget::getTargetType, "host"));
        if (!hosts.isEmpty()) {
            Long hostId = hosts.get(0).getId();
            result.put("avgCpu", avgMetric(hostId, "cpu.usage", since));
            result.put("avgMem", avgMetric(hostId, "mem.usage", since));
        } else {
            result.put("avgCpu", null);
            result.put("avgMem", null);
        }
        // alert24h / incidentOpen / topAlerts 由 alert 模块补充（跨模块经 service，M3 接入）
        result.put("alert24h", 0);
        result.put("incidentOpen", 0);
        result.put("topAlerts", List.of());
        return result;
    }

    private Double avgMetric(Long targetId, String metricKey, LocalDateTime since) {
        List<MetricData> list = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, targetId)
                .eq(MetricData::getMetricKey, metricKey)
                .ge(MetricData::getCollectTime, since));
        return list.stream().mapToDouble(m -> m.getMetricValue().doubleValue()).average().orElse(0);
    }
}
