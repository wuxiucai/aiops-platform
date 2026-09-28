package com.aiops.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则
 */
@Data
@TableName("alert_rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("target_id")
    private Long targetId;

    @TableField("group_id")
    private Long groupId;

    @TableField("metric_key")
    private String metricKey;

    /** static|baseline|ratio|trend */
    @TableField("rule_type")
    private String ruleType;

    /** gt|gte|lt|lte|outside|inside */
    @TableField("operator")
    private String operator;

    @TableField("threshold")
    private BigDecimal threshold;

    @TableField("duration_sec")
    private Integer durationSec;

    @TableField("level")
    private String level;

    @TableField("sensitivity")
    private BigDecimal sensitivity;

    /** baseline 配置 JSON：{days=7, k=3} */
    @TableField("baseline_config")
    private String baselineConfig;

    @TableField("silence_window")
    private String silenceWindow;

    /** 通知渠道 JSON，如 ["inapp","webhook:xxx"] */
    @TableField("notify_channels")
    private String notifyChannels;

    @TableField("enabled")
    private Integer enabled;

    @TableField("creator")
    private String creator;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
