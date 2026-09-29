package com.aiops.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 日志异常
 */
@Data
@TableName("log_anomaly")
public class LogAnomaly {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("template_id")
    private Long templateId;

    @TableField("datasource_id")
    private Long datasourceId;

    /** new_template|rare_template|spike|error_rate */
    @TableField("anomaly_type")
    private String anomalyType;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    @TableField("level")
    private String level;

    @TableField("trigger_value")
    private BigDecimal triggerValue;

    @TableField("baseline_value")
    private BigDecimal baselineValue;

    /** pending|processing|resolved|false_positive */
    @TableField("status")
    private String status;

    @TableField("first_time")
    private LocalDateTime firstTime;

    @TableField("last_time")
    private LocalDateTime lastTime;

    @TableField("count")
    private Integer count;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
