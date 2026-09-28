package com.aiops.module.incident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件时间线
 */
@Data
@TableName("incident_timeline")
public class IncidentTimeline {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("incident_id")
    private Long incidentId;

    @TableField("event_time")
    private LocalDateTime eventTime;

    /** trigger|aggregate|claim|comment|resolve|close|ai_analysis|log_analysis */
    @TableField("event_type")
    private String eventType;

    @TableField("description")
    private String description;

    @TableField("operator")
    private String operator;

    @TableField("ref_id")
    private Long refId;
}
