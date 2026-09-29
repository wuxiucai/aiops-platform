package com.aiops.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Drain 日志模板
 */
@Data
@TableName("log_template")
public class LogTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("datasource_id")
    private Long datasourceId;

    @TableField("index_config_id")
    private Long indexConfigId;

    @TableField("cluster_id")
    private Integer clusterId;

    @TableField("template_text")
    private String templateText;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("template_hash")
    private String templateHash;

    @TableField("first_seen")
    private LocalDateTime firstSeen;

    @TableField("last_seen")
    private LocalDateTime lastSeen;

    @TableField("total_count")
    private Long totalCount;

    @TableField("last_window_count")
    private Integer lastWindowCount;

    @TableField("sample_log")
    private String sampleLog;

    @TableField("variables")
    private String variables;

    @TableField("level")
    private String level;

    @TableField("service")
    private String service;

    /** 0 正常 1 已关注 2 已忽略 */
    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
