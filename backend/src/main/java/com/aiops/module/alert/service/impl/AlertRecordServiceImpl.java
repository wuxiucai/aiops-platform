package com.aiops.module.alert.service.impl;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.datasource.log.EsQueryBuilder;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.alert.service.AlertRecordService;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.entity.IncidentTimeline;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.incident.mapper.IncidentTimelineMapper;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警生命周期 + 关联日志检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRecordServiceImpl implements AlertRecordService {

    private final AlertRecordMapper alertRecordMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final IncidentTimelineMapper incidentTimelineMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;
    private final EsLogClient esLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 改状态：claim/resolve/close/false-positive；写 incident_timeline，必要时联动 incident */
    @Override
    @Transactional
    public void changeStatus(Long alertId, String action, String username, String remark) {
        AlertRecord alert = alertRecordMapper.selectById(alertId);
        if (alert == null) {
            throw new BizException("告警不存在");
        }
        String newStatus = switch (action) {
            case "claim" -> "processing";
            case "resolve" -> "resolved";
            case "close" -> "closed";
            case "false-positive" -> "false_positive";
            default -> throw new BizException("不支持的操作: " + action);
        };
        alert.setStatus(newStatus);
        if ("claim".equals(action)) {
            alert.setClaimedBy(username);
            alert.setClaimedTime(LocalDateTime.now());
        } else if ("resolve".equals(action) || "false-positive".equals(action)) {
            alert.setResolvedBy(username);
            alert.setResolvedTime(LocalDateTime.now());
        }
        if (remark != null && !remark.isBlank()) {
            alert.setHandleRemark(remark);
        }
        alertRecordMapper.updateById(alert);
        log.info("[Alert] 告警#{} {} → {} by {}", alertId, action, newStatus, username);

        // 写时间线 + 联动 incident
        if (alert.getIncidentId() != null) {
            IncidentTimeline tl = new IncidentTimeline();
            tl.setIncidentId(alert.getIncidentId());
            tl.setEventTime(LocalDateTime.now());
            tl.setEventType(action);
            tl.setDescription("告警 #" + alertId + " " + action
                    + (remark == null || remark.isBlank() ? "" : ("：" + remark)));
            tl.setOperator(username);
            tl.setRefId(alertId);
            incidentTimelineMapper.insert(tl);

            // 若所有关联告警都 resolved/closed/false_positive → incident 也置 resolved
            if ("resolve".equals(action) || "false-positive".equals(action) || "close".equals(action)) {
                Long pendingCnt = alertRecordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                        .eq(AlertRecord::getIncidentId, alert.getIncidentId())
                        .in(AlertRecord::getStatus, "pending", "processing"));
                if (pendingCnt == 0) {
                    AlertIncident upd = new AlertIncident();
                    upd.setId(alert.getIncidentId());
                    upd.setStatus("resolved");
                    upd.setEndTime(LocalDateTime.now());
                    alertIncidentMapper.updateById(upd);
                    log.info("[Incident] 事件#{} 联动置 resolved", alert.getIncidentId());
                }
            }
        }
    }

    /** ES 关联日志检索：aiops-log-* 中 service=logServiceName，时间窗 ± 10min */
    @Override
    public Map<String, Object> relatedLogs(Long alertId) {
        AlertRecord alert = alertRecordMapper.selectById(alertId);
        if (alert == null) {
            throw new BizException("告警不存在");
        }
        MonitorTarget target = monitorTargetMapper.selectById(alert.getTargetId());
        if (target == null || target.getLogServiceName() == null || target.getLogServiceName().isBlank()) {
            throw new BizException("该告警 target 未配置 log_service_name");
        }
        EsDatasource ds = pickDefaultDatasource();
        EsIndexConfig idx = pickDefaultIndexConfig(ds.getId());
        LocalDateTime anchor = alert.getFirstTriggerTime() == null
                ? alert.getCreateTime() : alert.getFirstTriggerTime();
        LocalDateTime start = anchor.minusMinutes(10);
        LocalDateTime end = anchor.plusMinutes(10);

        EsQueryBuilder.SearchParams p = new EsQueryBuilder.SearchParams(
                idx.getIndexPattern(),
                idx.getTimeField(), idx.getMessageField(), idx.getLevelField(),
                idx.getServiceField(), idx.getTraceIdField(),
                start, end,
                null,
                java.util.List.of(target.getLogServiceName()),
                null, null,
                1, 50,
                "standard"
        );
        String dsl = EsQueryBuilder.buildSearchDsl(p);
        log.info("[Alert] relatedLogs alertId={}, dsl=\n{}", alertId, dsl);
        String resp = esLogClient.postSearch(ds.baseUrl(), idx.getIndexPattern() + "/_search", dsl);

        Map<String, Object> out = new HashMap<>();
        out.put("dsl", dsl);
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode hits = root.path("hits");
            out.put("total", hits.path("total").path("value").asLong(0));
            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> row = new HashMap<>();
                row.put("time", src.path(idx.getTimeField()).asText(null));
                row.put("level", src.path(idx.getLevelField()).asText(null));
                row.put("service", src.path(idx.getServiceField()).asText(null));
                row.put("message", src.path(idx.getMessageField()).asText(null));
                row.put("traceId", src.path(idx.getTraceIdField()).asText(null));
                records.add(row);
            }
            out.put("records", records);
        } catch (Exception e) {
            throw new BizException("ES 响应解析失败: " + e.getMessage());
        }
        return out;
    }

    /** 取默认 ES 数据源（status=1 第一条） */
    private EsDatasource pickDefaultDatasource() {
        List<EsDatasource> list = esDatasourceMapper.selectList(
                new LambdaQueryWrapper<EsDatasource>().eq(EsDatasource::getStatus, 1)
                        .orderByAsc(EsDatasource::getId));
        if (list.isEmpty()) {
            throw new BizException("未配置 ES 数据源");
        }
        return list.get(0);
    }

    private EsIndexConfig pickDefaultIndexConfig(Long datasourceId) {
        List<EsIndexConfig> list = esIndexConfigMapper.selectList(
                new LambdaQueryWrapper<EsIndexConfig>()
                        .eq(EsIndexConfig::getDatasourceId, datasourceId)
                        .orderByAsc(EsIndexConfig::getId));
        if (list.isEmpty()) {
            throw new BizException("未配置 ES 索引");
        }
        return list.get(0);
    }
}
