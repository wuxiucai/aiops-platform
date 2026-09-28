package com.aiops.module.esa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 字段探测缓存
 */
@Data
@TableName("es_field_cache")
public class EsFieldCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("datasource_id")
    private Long datasourceId;

    @TableField("index_pattern")
    private String indexPattern;

    @TableField("field_name")
    private String fieldName;

    @TableField("field_type")
    private String fieldType;

    @TableField("sample_value")
    private String sampleValue;

    @TableField("last_scan_time")
    private LocalDateTime lastScanTime;
}
