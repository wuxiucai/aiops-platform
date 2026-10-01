package com.aiops.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NL2ES-DSL 查询日志（任务书 §3 nl_query_log）。
 * 每次提问永入一行，含 validated/executed/hit/count/retryCount/latency。
 */
@Data
@TableName("nl_query_log")
public class NlQueryLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 用户自然语言问题（UTF-8， 不缩写） */
    @TableField("question")
    private String question;

    /** LLM 生成的 DSL JSON （原文 string) */
    @TableField("generated_dsl")
    private String generatedDsl;

    /** 校验状态： 0-rejected, 1-passed */
    @TableField("validated")
    private Integer validated;

    @TableField("executed")
    private Integer executed;

    /** _search 返回 total hits （若没执行 NULL) */
    @TableField("hit_count")
    private Integer hitCount;

    /** ln_answer 中文总结（可选） */
    @TableField("answer")
    private String answer;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("create_time")
    private LocalDateTime createTime;
}
