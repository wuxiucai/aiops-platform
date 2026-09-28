package com.aiops.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标数据
 */
@Data
@TableName("metric_data")
public class MetricData {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("target_id")
    private Long targetId;

    @TableField("metric_key")
    private String metricKey;

    @TableField("metric_value")
    private BigDecimal metricValue;

    @TableField("collect_time")
    private LocalDateTime collectTime;
}
