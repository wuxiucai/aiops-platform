package com.aiops.module.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 相似检索日志（任务书 §3 kb_similarity_log）
 */
@Data
@TableName("kb_similarity_log")
public class KbSimilarityLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("incident_id")
    private Long incidentId;

    @TableField("case_id")
    private Long caseId;

    /** similarity 得分（0-1） */
    @TableField("score")
    private BigDecimal score;

    @TableField("is_adopted")
    private Integer isAdopted;

    @TableField("create_time")
    private LocalDateTime createTime;
}
