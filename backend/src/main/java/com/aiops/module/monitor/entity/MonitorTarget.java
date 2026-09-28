package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监控对象
 */
@Data
@TableName("monitor_target")
public class MonitorTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** host|service */
    @TableField("target_type")
    private String targetType;

    @TableField("ip")
    private String ip;

    @TableField("port")
    private Integer port;

    @TableField("os")
    private String os;

    @TableField("group_id")
    private Long groupId;

    @TableField("tags")
    private String tags;

    @TableField("owner")
    private String owner;

    @TableField("description")
    private String description;

    @TableField("agent_status")
    private String agentStatus;

    @TableField("last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @TableField("status")
    private Integer status;

    /** 打通指标与日志：告警时按它去 ES 查日志 */
    @TableField("log_service_name")
    private String logServiceName;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
