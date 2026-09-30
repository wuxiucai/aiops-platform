package com.aiops.module.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库 - 故障案例（任务书 §3 kb_fault_case）
 */
@Data
@TableName("kb_fault_case")
public class KbFaultCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("symptom")
    private String symptom;

    @TableField("root_cause")
    private String rootCause;

    @TableField("solution")
    private String solution;

    /** JSON array（tags） */
    @TableField("tags")
    private String tags;

    @TableField("occurred_time")
    private LocalDateTime occurredTime;

    @TableField("related_incident_id")
    private Long relatedIncidentId;

    /** seed 来源：incident:<id> 或 manual */
    @TableField("source")
    private String source;

    /** embedding JSON array string */
    @TableField("embedding")
    private String embedding;

    @TableField("embedding_status")
    private String embeddingStatus;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("deleted")
    private Integer deleted;
}
