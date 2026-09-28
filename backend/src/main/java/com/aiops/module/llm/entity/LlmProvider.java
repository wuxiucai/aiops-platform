package com.aiops.module.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 供应商配置
 */
@Data
@TableName("llm_provider")
public class LlmProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** openai_compatible|ollama|custom */
    @TableField("provider_type")
    private String providerType;

    @TableField("base_url")
    private String baseUrl;

    /** AES 加密存储 */
    @TableField("api_key")
    private String apiKey;

    @TableField("model_name")
    private String modelName;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("temperature")
    private BigDecimal temperature;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("timeout_ms")
    private Integer timeoutMs;

    @TableField("is_default")
    private Integer isDefault;

    @TableField("status")
    private Integer status;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
