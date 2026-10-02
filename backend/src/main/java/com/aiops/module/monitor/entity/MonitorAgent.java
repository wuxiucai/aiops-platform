package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远程 Linux agent（Push 模式）。
 * agent 主动 POST metric + heartbeat 到平台，X-Agent-Key 鉴权。
 */
@Data
@TableName("monitor_agent")
public class MonitorAgent {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("target_id")
    private Long targetId;

    /** 64 位随机密钥，生成后下发给 agent */
    @TableField("agent_key")
    private String agentKey;

    /** 0 禁用 / 1 启用 */
    @TableField("status")
    private Integer status;

    @TableField("version")
    private String version;

    @TableField("last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @TableField("last_metric_time")
    private LocalDateTime lastMetricTime;

    @TableField("install_command")
    private String installCommand;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("deleted")
    private Integer deleted;
}
