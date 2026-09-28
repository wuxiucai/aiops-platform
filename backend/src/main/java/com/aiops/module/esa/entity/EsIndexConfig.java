package com.aiops.module.esa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 索引与字段映射配置
 */
@Data
@TableName("es_index_config")
public class EsIndexConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("datasource_id")
    private Long datasourceId;

    @TableField("name")
    private String name;

    /** 如 log-* */
    @TableField("index_pattern")
    private String indexPattern;

    @TableField("time_field")
    private String timeField;

    @TableField("message_field")
    private String messageField;

    @TableField("level_field")
    private String levelField;

    @TableField("service_field")
    private String serviceField;

    @TableField("trace_id_field")
    private String traceIdField;

    /** {"ERROR":["ERROR","err","SEVERE"]} */
    @TableField("level_mapping")
    private String levelMapping;

    @TableField("default_time_range_hours")
    private Integer defaultTimeRangeHours;

    @TableField("enabled")
    private Integer enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
