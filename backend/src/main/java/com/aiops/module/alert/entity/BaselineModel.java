package com.aiops.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 基线模型（hour_bucket：周一~周日 × 0~23 共 168 桶）
 */
@Data
@TableName("baseline_model")
public class BaselineModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("target_id")
    private Long targetId;

    @TableField("metric_key")
    private String metricKey;

    /** 3sigma|ewma|stl|hour_bucket */
    @TableField("model_type")
    private String modelType;

    @TableField("params")
    private String params;

    /** 如 MON-14 */
    @TableField("bucket_key")
    private String bucketKey;

    @TableField("upper_bound")
    private BigDecimal upperBound;

    @TableField("lower_bound")
    private BigDecimal lowerBound;

    @TableField("mean_value")
    private BigDecimal meanValue;

    @TableField("std_value")
    private BigDecimal stdValue;

    @TableField("sample_count")
    private Integer sampleCount;

    @TableField("train_start")
    private LocalDateTime trainStart;

    @TableField("train_end")
    private LocalDateTime trainEnd;

    @TableField("last_train_time")
    private LocalDateTime lastTrainTime;

    @TableField("status")
    private Integer status;
}
