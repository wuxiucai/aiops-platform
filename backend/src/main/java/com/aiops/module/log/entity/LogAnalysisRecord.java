package com.aiops.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 日志分析记录（论文 input_summary 展示专用）
 */
@Data
@TableName("log_analysis_record")
public class LogAnalysisRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("datasource_id")
    private Long datasourceId;

    @TableField("index_config_id")
    private Long indexConfigId;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("ref_id")
    private Long refId;

    @TableField("time_start")
    private LocalDateTime timeStart;

    @TableField("time_end")
    private LocalDateTime timeEnd;

    @TableField("log_count")
    private Integer logCount;

    @TableField("template_count")
    private Integer templateCount;

    @TableField("input_summary")
    private String inputSummary;

    @TableField("result")
    private String result;

    @TableField("token_cost")
    private Integer tokenCost;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("status")
    private String status;

    @TableField("create_time")
    private LocalDateTime createTime;
}
