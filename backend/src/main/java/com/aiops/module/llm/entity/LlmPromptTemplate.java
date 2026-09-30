package com.aiops.module.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 提示词模板（任务书 §3 llm_prompt_template）。
 * scene_code 唯一（log_explain / alert_explain / root_cause / nl_query / kb_question ...）。
 */
@Data
@TableName("llm_prompt_template")
public class LlmPromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("scene_name")
    private String sceneName;

    @TableField("system_prompt")
    private String systemPrompt;

    /** 用户消息模板：${var} 占位，由调用方替换 */
    @TableField("user_prompt_tpl")
    private String userPromptTpl;

    /** 期望输出 schema（JSON Schema）。M5 起强校验 */
    @TableField("output_schema")
    private String outputSchema;

    @TableField("version")
    private Integer version;

    @TableField("enabled")
    private Integer enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
