package com.aiops.module.incident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 事件-告警 关联
 */
@Data
@TableName("incident_alert_rel")
public class IncidentAlertRel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("incident_id")
    private Long incidentId;

    @TableField("alert_id")
    private Long alertId;
}
