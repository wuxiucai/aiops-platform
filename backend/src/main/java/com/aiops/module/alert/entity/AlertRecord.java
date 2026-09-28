package com.aiops.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警记录
 */
@Data
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("target_id")
    private Long targetId;

    @TableField("metric_key")
    private String metricKey;

    @TableField("level")
    private String level;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("trigger_value")
    private BigDecimal triggerValue;

    @TableField("threshold_value")
    private BigDecimal thresholdValue;

    @TableField("baseline_upper")
    private BigDecimal baselineUpper;

    @TableField("baseline_lower")
    private BigDecimal baselineLower;

    /** pending|processing|resolved|closed|false_positive */
    @TableField("status")
    private String status;

    @TableField("dedup_key")
    private String dedupKey;

    @TableField("first_trigger_time")
    private LocalDateTime firstTriggerTime;

    @TableField("last_trigger_time")
    private LocalDateTime lastTriggerTime;

    @TableField("trigger_count")
    private Integer triggerCount;

    @TableField("claimed_by")
    private String claimedBy;

    @TableField("claimed_time")
    private LocalDateTime claimedTime;

    @TableField("resolved_by")
    private String resolvedBy;

    @TableField("resolved_time")
    private LocalDateTime resolvedTime;

    @TableField("handle_remark")
    private String handleRemark;

    @TableField("incident_id")
    private Long incidentId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
