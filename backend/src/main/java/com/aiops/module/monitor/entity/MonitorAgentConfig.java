package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 侧个性化配置（agent_id + config_key 唯一）。
 * 平台可调 agent 采集间隔、特定日志尾接路径等。
 */
@Data
@TableName("monitor_agent_config")
public class MonitorAgentConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("agent_id")
    private Long agentId;

    /** 形如 collect.interval.sec / log.path */
    @TableField("config_key")
    private String configKey;

    @TableField("config_value")
    private String configValue;
}
