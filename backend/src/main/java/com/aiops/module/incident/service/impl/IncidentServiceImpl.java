package com.aiops.module.incident.service.impl;

import com.aiops.common.BizException;
import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.entity.IncidentAlertRel;
import com.aiops.module.incident.entity.IncidentTimeline;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.incident.mapper.IncidentAlertRelMapper;
import com.aiops.module.incident.mapper.IncidentTimelineMapper;
import com.aiops.module.incident.service.IncidentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 故障事件：详情聚合 + 一键解决 + 时间线维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentServiceImpl implements IncidentService {

    private final AlertIncidentMapper alertIncidentMapper;
    private final IncidentAlertRelMapper incidentAlertRelMapper;
    private final IncidentTimelineMapper incidentTimelineMapper;
    private final AlertRecordMapper alertRecordMapper;

    @Override
    public Map<String, Object> detail(Long incidentId) {
        AlertIncident incident = alertIncidentMapper.selectById(incidentId);
        if (incident == null) {
            throw new BizException("事件不存在");
        }
        List<IncidentAlertRel> rels = incidentAlertRelMapper.selectList(
                new LambdaQueryWrapper<IncidentAlertRel>()
                        .eq(IncidentAlertRel::getIncidentId, incidentId));
        List<Long> alertIds = rels.stream().map(IncidentAlertRel::getAlertId).toList();
        List<AlertRecord> alerts = alertIds.isEmpty() ? List.of()
                : alertRecordMapper.selectBatchIds(alertIds);
        List<IncidentTimeline> timeline = incidentTimelineMapper.selectList(
                new LambdaQueryWrapper<IncidentTimeline>()
                        .eq(IncidentTimeline::getIncidentId, incidentId)
                        .orderByAsc(IncidentTimeline::getEventTime));

        Map<String, Object> out = new HashMap<>();
        out.put("incident", incident);
        out.put("alerts", alerts);
        out.put("timeline", timeline);
        return out;
    }

    @Override
    @Transactional
    public void resolve(Long incidentId, String operator, String remark) {
        AlertIncident incident = alertIncidentMapper.selectById(incidentId);
        if (incident == null) {
            throw new BizException("事件不存在");
        }
        // 所有关联告警置 resolved
        List<IncidentAlertRel> rels = incidentAlertRelMapper.selectList(
                new LambdaQueryWrapper<IncidentAlertRel>()
                        .eq(IncidentAlertRel::getIncidentId, incidentId));
        LocalDateTime now = LocalDateTime.now();
        for (IncidentAlertRel rel : rels) {
            AlertRecord upd = new AlertRecord();
            upd.setId(rel.getAlertId());
            upd.setStatus("resolved");
            upd.setResolvedBy(operator);
            upd.setResolvedTime(now);
            alertRecordMapper.updateById(upd);
        }
        AlertIncident upd = new AlertIncident();
        upd.setId(incidentId);
        upd.setStatus("resolved");
        upd.setEndTime(now);
        if (incident.getStartTime() != null) {
            upd.setDurationSec(Duration.between(incident.getStartTime(), now).toSeconds());
        }
        alertIncidentMapper.updateById(upd);
        log.info("[Incident] 事件#{} 已解决 operator={}", incidentId, operator);

        IncidentTimeline tl = new IncidentTimeline();
        tl.setIncidentId(incidentId);
        tl.setEventTime(now);
        tl.setEventType("resolve");
        tl.setDescription("事件已解决" + (remark == null || remark.isBlank() ? "" : ("：" + remark)));
        tl.setOperator(operator);
        incidentTimelineMapper.insert(tl);
    }

    @Override
    public void appendTimeline(Long incidentId, String eventType, String description, String operator) {
        if (alertIncidentMapper.selectById(incidentId) == null) {
            throw new BizException("事件不存在");
        }
        IncidentTimeline tl = new IncidentTimeline();
        tl.setIncidentId(incidentId);
        tl.setEventTime(LocalDateTime.now());
        tl.setEventType(eventType == null || eventType.isBlank() ? "comment" : eventType);
        tl.setDescription(description);
        tl.setOperator(operator);
        incidentTimelineMapper.insert(tl);
    }
}
