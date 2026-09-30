package com.aiops.module.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用日志（任务书 §3 llm_call_log DDL 原样映射）。
 * status: success|fail|timeout
 */
@Data
@TableName("llm_call_log")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("provider_id")
    private Long providerId;

    @TableField("scene_code")
    private String sceneCode;

    /** 关联对象 ID（如 log_template.id / alert_record.id / alert_incident.id） */
    @TableField("ref_id")
    private Long refId;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("latency_ms")
    private Long latencyMs;

    /** success | fail | timeout */
    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("create_time")
    private LocalDateTime createTime;

    /** 估算成本（人民币元，DeepSeek 约 ¥0.01/1K tokens），按总量粗算 */
    @TableField(exist = false)
    private Double estimatedCost;
}
