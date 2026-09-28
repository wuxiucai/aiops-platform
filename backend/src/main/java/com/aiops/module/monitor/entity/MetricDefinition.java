package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 指标定义
 */
@Data
@TableName("metric_definition")
public class MetricDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("metric_key")
    private String metricKey;

    @TableField("metric_name")
    private String metricName;

    @TableField("unit")
    private String unit;

    @TableField("category")
    private String category;

    @TableField("value_type")
    private String valueType;

    @TableField("description")
    private String description;

    @TableField("default_threshold")
    private BigDecimal defaultThreshold;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
