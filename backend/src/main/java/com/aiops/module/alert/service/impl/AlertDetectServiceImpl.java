package com.aiops.module.alert.service.impl;

import com.aiops.module.alert.entity.AlertRecord;
import com.aiops.module.alert.entity.AlertRule;
import com.aiops.module.alert.entity.AlertSilence;
import com.aiops.module.alert.entity.BaselineModel;
import com.aiops.module.alert.mapper.AlertRecordMapper;
import com.aiops.module.alert.mapper.AlertRuleMapper;
import com.aiops.module.alert.mapper.AlertSilenceMapper;
import com.aiops.module.alert.mapper.BaselineModelMapper;
import com.aiops.module.alert.service.AlertDetectService;
import com.aiops.module.incident.entity.AlertIncident;
import com.aiops.module.incident.entity.IncidentAlertRel;
import com.aiops.module.incident.entity.IncidentTimeline;
import com.aiops.module.incident.mapper.AlertIncidentMapper;
import com.aiops.module.incident.mapper.IncidentAlertRelMapper;
import com.aiops.module.incident.mapper.IncidentTimelineMapper;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.aiops.module.notify.service.NotifyService;
import com.aiops.module.system.entity.SysUser;
import com.aiops.module.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 告警检测核心：§5.5.2 步骤一一对应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertDetectServiceImpl implements AlertDetectService {

    private static final Random RANDOM = new Random();
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AlertRuleMapper alertRuleMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AlertSilenceMapper alertSilenceMapper;
    private final BaselineModelMapper baselineModelMapper;
    private final MetricDataMapper metricDataMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final IncidentAlertRelMapper incidentAlertRelMapper;
    private final IncidentTimelineMapper incidentTimelineMapper;
    private final NotifyService notifyService;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional
    public boolean processRule(Long ruleId) {
        AlertRule rule = alertRuleMapper.selectById(ruleId);
        if (rule == null || rule.getEnabled() == null || rule.getEnabled() != 1) {
            return false;
        }
        if (rule.getTargetId() == null) {
            return false;
        }
        // 1. 取最新一条 metric_data
        List<MetricData> latest = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, rule.getTargetId())
                .eq(MetricData::getMetricKey, rule.getMetricKey())
                .orderByDesc(MetricData::getCollectTime)
                .last("LIMIT 1"));
        if (latest.isEmpty()) {
            return false;
        }
        MetricData recent = latest.get(0);
        double value = recent.getMetricValue().doubleValue();

        // 2. 触发判定（static / baseline）
        BigDecimal baselineUpper = null;
        BigDecimal baselineLower = null;
        boolean triggered;
        if ("baseline".equalsIgnoreCase(rule.getRuleType())) {
            String bucket = recent.getCollectTime()
                    .format(AlertRuleServiceImpl.BUCKET_FMT).toUpperCase(Locale.ENGLISH);
            BaselineModel bm = baselineModelMapper.selectOne(new LambdaQueryWrapper<BaselineModel>()
                    .eq(BaselineModel::getRuleId, rule.getId())
                    .eq(BaselineModel::getTargetId, rule.getTargetId())
                    .eq(BaselineModel::getMetricKey, rule.getMetricKey())
                    .eq(BaselineModel::getModelType, "hour_bucket")
                    .eq(BaselineModel::getBucketKey, bucket)
                    .last("LIMIT 1"));
            if (bm == null) {
                return false;
            }
            baselineUpper = bm.getUpperBound();
            baselineLower = bm.getLowerBound();
            triggered = value > bm.getUpperBound().doubleValue()
                    || value < bm.getLowerBound().doubleValue();
        } else {
            triggered = AlertRuleServiceImpl.compare(value, rule.getOperator(),
                    rule.getThreshold() == null ? null : rule.getThreshold().doubleValue());
        }
        if (!triggered) {
            // §5.5.2 步骤2 后半句"否则当作恢复"：条件不再满足 → 关闭该 dedup_key 下的活跃告警
            autoResolve(rule, value);
            return false;
        }

        // 3. duration_sec 持续判定：窗口内全部样本都满足触发条件才算持续越界（§5.5.2 步骤2）
        int durationSec = rule.getDurationSec() == null ? 60 : rule.getDurationSec();
        LocalDateTime since = LocalDateTime.now().minusSeconds(durationSec);
        List<MetricData> windowData = metricDataMapper.selectList(new LambdaQueryWrapper<MetricData>()
                .eq(MetricData::getTargetId, rule.getTargetId())
                .eq(MetricData::getMetricKey, rule.getMetricKey())
                .ge(MetricData::getCollectTime, since)
                .orderByAsc(MetricData::getCollectTime));
        if (windowData.size() < 2) {
            return false;
        }
        // 全部点都越界才算是"持续"，否则视为抖动恢复
        boolean sustained = true;
        for (MetricData md : windowData) {
            double v = md.getMetricValue().doubleValue();
            boolean hit;
            if ("baseline".equalsIgnoreCase(rule.getRuleType())) {
                hit = baselineUpper != null
                        && (v > baselineUpper.doubleValue() || v < baselineLower.doubleValue());
            } else {
                hit = AlertRuleServiceImpl.compare(v, rule.getOperator(),
                        rule.getThreshold() == null ? null : rule.getThreshold().doubleValue());
            }
            if (!hit) {
                sustained = false;
                break;
            }
        }
        if (!sustained) {
            // 抖动/回落 → 同样视为恢复（§5.5.2：不满足持续时长就不该保持活跃告警）
            autoResolve(rule, value);
            return false;
        }

        String dedupKey = "rule_" + rule.getId() + "_target_" + rule.getTargetId()
                + "_metric_" + rule.getMetricKey();
        LocalDateTime dedupSince = LocalDateTime.now().minusMinutes(10);

        // 10min 内同 dedup_key 仍未结的告警 → 合并更新（窗口以"上次触发时间"为准：
        // 问题持续存在就一直合并，超过 10min 无新触发才允许下一轮新建 —— §5.5.2 步骤3）
        AlertRecord existed = alertRecordMapper.selectOne(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getDedupKey, dedupKey)
                .in(AlertRecord::getStatus, "pending", "processing")
                .ge(AlertRecord::getLastTriggerTime, dedupSince)
                .orderByDesc(AlertRecord::getId)
                .last("LIMIT 1"));

        // 4. 静默检查
        boolean silenced = isSilenced(rule);
        // 5. 抑制检查
        boolean suppressed = isSuppressed(rule.getTargetId());

        if (existed != null) {
            existed.setLastTriggerTime(LocalDateTime.now());
            existed.setTriggerCount((existed.getTriggerCount() == null ? 1 : existed.getTriggerCount()) + 1);
            existed.setTriggerValue(recent.getMetricValue());
            if (silenced || suppressed) {
                existed.setStatus("closed");
            }
            alertRecordMapper.updateById(existed);
            return true;
        }

        // 新建告警
        AlertRecord record = new AlertRecord();
        record.setRuleId(rule.getId());
        record.setTargetId(rule.getTargetId());
        record.setMetricKey(rule.getMetricKey());
        record.setLevel(rule.getLevel() == null ? "WARN" : rule.getLevel());
        record.setTitle(buildTitle(rule, recent));
        record.setContent(buildContent(rule, recent, baselineUpper, baselineLower));
        record.setTriggerValue(recent.getMetricValue());
        record.setThresholdValue(rule.getThreshold());
        record.setBaselineUpper(baselineUpper);
        record.setBaselineLower(baselineLower);
        record.setDedupKey(dedupKey);
        record.setFirstTriggerTime(LocalDateTime.now());
        record.setLastTriggerTime(LocalDateTime.now());
        record.setTriggerCount(1);
        record.setStatus((silenced || suppressed) ? "closed" : "pending");
        alertRecordMapper.insert(record);
        log.info("[Alert] 新建告警 id={}, rule={}, target={}, value={}, silenced={}, suppressed={}",
                record.getId(), rule.getId(), rule.getTargetId(), value, silenced, suppressed);

        if (silenced || suppressed) {
            return true;
        }

        // 6. 关联 incident：5min 内同 target / 同 group 有 open → 关联；否则新建
        Long incidentId = linkOrCreateIncident(rule, record);
        AlertRecord upd = new AlertRecord();
        upd.setId(record.getId());
        upd.setIncidentId(incidentId);
        alertRecordMapper.updateById(upd);

        // 7. 通知
        Long creatorUserId = findCreatorUserId(rule.getCreator());
        notifyService.sendAlert(rule, record, creatorUserId);
        return true;
    }

    /**
     * 自动恢复（§5.5.2 步骤 2 的"否则当作恢复"）：
     * 规则条件不再满足（或抖动未持续）时，把同 dedup_key 下仍处于 pending/processing 的告警置为 resolved，
     * 并写入 incident_timeline。已被人为 close/误报的记录不动。
     */
    private void autoResolve(AlertRule rule, double currentValue) {
        String dedupKey = "rule_" + rule.getId() + "_target_" + rule.getTargetId()
                + "_metric_" + rule.getMetricKey();
        List<AlertRecord> actives = alertRecordMapper.selectList(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getDedupKey, dedupKey)
                .in(AlertRecord::getStatus, List.of("pending", "processing")));
        if (actives.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AlertRecord a : actives) {
            AlertRecord upd = new AlertRecord();
            upd.setId(a.getId());
            upd.setStatus("resolved");
            upd.setUpdateTime(now);
            alertRecordMapper.updateById(upd);
            log.info("[AlertDetect] auto-resolved alert#{} (dedupKey={}, value={})",
                    a.getId(), dedupKey, currentValue);
            // incident_timeline 留痕
            if (a.getIncidentId() != null) {
                try {
                    IncidentTimeline tl = new IncidentTimeline();
                    tl.setIncidentId(a.getIncidentId());
                    tl.setEventType("alert_auto_resolved");
                    tl.setDescription("指标恢复正常，告警自动恢复：" + a.getTitle());
                    tl.setOperator("system");
                    tl.setEventTime(now);
                    tl.setRefId(a.getId());
                    incidentTimelineMapper.insert(tl);
                } catch (Exception e) {
                    log.warn("[AlertDetect] 写 incident_timeline 失败: {}", e.getMessage());
                }
            }
        }
    }

    /** 静默：命中 enabled & 当前时间在 [start,end] 的 alert_silence */
    private boolean isSilenced(AlertRule rule) {
        LocalDateTime now = LocalDateTime.now();
        List<AlertSilence> list = alertSilenceMapper.selectList(new LambdaQueryWrapper<AlertSilence>()
                .eq(AlertSilence::getStatus, 1)
                .le(AlertSilence::getStartTime, now)
                .ge(AlertSilence::getEndTime, now));
        for (AlertSilence s : list) {
            boolean targetMatch = s.getTargetId() == null || s.getTargetId().equals(rule.getTargetId());
            boolean ruleMatch = s.getRuleId() == null || s.getRuleId().equals(rule.getId());
            if (targetMatch && ruleMatch) {
                return true;
            }
        }
        return false;
    }

    /** 抑制：service 类型，若其 host 状态 down 则不发 */
    private boolean isSuppressed(Long targetId) {
        MonitorTarget t = monitorTargetMapper.selectById(targetId);
        if (t == null) {
            return false;
        }
        if (!"service".equalsIgnoreCase(t.getTargetType())) {
            return false;
        }
        List<MonitorTarget> hosts = monitorTargetMapper.selectList(
                new LambdaQueryWrapper<MonitorTarget>()
                        .eq(MonitorTarget::getTargetType, "host"));
        for (MonitorTarget host : hosts) {
            if (host.getStatus() != null && host.getStatus() == 0) {
                return true;
            }
            if ("down".equalsIgnoreCase(host.getAgentStatus())) {
                return true;
            }
        }
        return false;
    }

    /** 关联/新建 incident，并写时间线 */
    private Long linkOrCreateIncident(AlertRule rule, AlertRecord record) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        List<AlertIncident> open = alertIncidentMapper.selectList(new LambdaQueryWrapper<AlertIncident>()
                .eq(AlertIncident::getStatus, "open")
                .ge(AlertIncident::getStartTime, since)
                .orderByDesc(AlertIncident::getId));
        Long chosen = null;
        for (AlertIncident in : open) {
            if (in.getPrimaryTargetId() != null && in.getPrimaryTargetId().equals(rule.getTargetId())) {
                chosen = in.getId();
                break;
            }
        }
        if (chosen == null && rule.getGroupId() != null) {
            List<MonitorTarget> siblings = monitorTargetMapper.selectList(
                    new LambdaQueryWrapper<MonitorTarget>().eq(MonitorTarget::getGroupId, rule.getGroupId()));
            java.util.Set<Long> sibIds = new java.util.HashSet<>();
            for (MonitorTarget s : siblings) sibIds.add(s.getId());
            for (AlertIncident in : open) {
                if (in.getPrimaryTargetId() != null && sibIds.contains(in.getPrimaryTargetId())) {
                    chosen = in.getId();
                    break;
                }
            }
        }
        if (chosen == null) {
            AlertIncident in = new AlertIncident();
            in.setIncidentNo("INC" + LocalDateTime.now().format(NO_FMT)
                    + String.format("%03d", RANDOM.nextInt(1000)));
            in.setTitle(record.getTitle());
            in.setLevel(record.getLevel());
            in.setStatus("open");
            in.setStartTime(LocalDateTime.now());
            in.setPrimaryTargetId(rule.getTargetId());
            in.setAlertCount(0);
            in.setAnalysisStatus("none");
            alertIncidentMapper.insert(in);
            chosen = in.getId();
            log.info("[Incident] 新建事件 incidentNo={}, id={}", in.getIncidentNo(), chosen);
        }
        IncidentAlertRel rel = new IncidentAlertRel();
        rel.setIncidentId(chosen);
        rel.setAlertId(record.getId());
        try {
            incidentAlertRelMapper.insert(rel);
        } catch (Exception ignore) { /* 唯一键冲突忽略 */ }
        AlertIncident cur = alertIncidentMapper.selectById(chosen);
        AlertIncident upd = new AlertIncident();
        upd.setId(chosen);
        upd.setAlertCount((cur.getAlertCount() == null ? 0 : cur.getAlertCount()) + 1);
        alertIncidentMapper.updateById(upd);

        IncidentTimeline tl = new IncidentTimeline();
        tl.setIncidentId(chosen);
        tl.setEventTime(LocalDateTime.now());
        tl.setEventType("trigger");
        tl.setDescription("告警 #" + record.getId() + " 触发：" + record.getTitle());
        tl.setOperator("system");
        tl.setRefId(record.getId());
        incidentTimelineMapper.insert(tl);
        return chosen;
    }

    private Long findCreatorUserId(String username) {
        if (username == null || username.isBlank()) {
            return 1L;
        }
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username).last("LIMIT 1"));
        return u == null ? 1L : u.getId();
    }

    private String buildTitle(AlertRule rule, MetricData data) {
        return "[" + (rule.getLevel() == null ? "WARN" : rule.getLevel()) + "] "
                + rule.getName() + " - " + rule.getMetricKey()
                + " = " + data.getMetricValue();
    }

    private String buildContent(AlertRule rule, MetricData data, BigDecimal upper, BigDecimal lower) {
        if ("baseline".equalsIgnoreCase(rule.getRuleType())) {
            return "规则[" + rule.getName() + "] 基线越界：metric=" + rule.getMetricKey()
                    + ", value=" + data.getMetricValue()
                    + ", upper=" + upper + ", lower=" + lower;
        }
        return "规则[" + rule.getName() + "] 阈值越界：metric=" + rule.getMetricKey()
                + ", value=" + data.getMetricValue()
                + ", operator=" + rule.getOperator()
                + ", threshold=" + rule.getThreshold();
    }
}
