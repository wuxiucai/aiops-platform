package com.aiops.module.incident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 故障事件
 */
@Data
@TableName("alert_incident")
public class AlertIncident {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("incident_no")
    private String incidentNo;

    @TableField("title")
    private String title;

    @TableField("level")
    private String level;

    /** open|processing|resolved|closed */
    @TableField("status")
    private String status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("duration_sec")
    private Long durationSec;

    @TableField("primary_target_id")
    private Long primaryTargetId;

    @TableField("alert_count")
    private Integer alertCount;

    @TableField("llm_summary")
    private String llmSummary;

    @TableField("llm_root_cause")
    private String llmRootCause;

    @TableField("llm_suggestion")
    private String llmSuggestion;

    @TableField("llm_report")
    private String llmReport;

    @TableField("llm_log_evidence")
    private String llmLogEvidence;

    @TableField("analysis_status")
    private String analysisStatus;

    @TableField("analysis_time")
    private LocalDateTime analysisTime;

    @TableField("similar_case_id")
    private Long similarCaseId;

    @TableField("similar_score")
    private BigDecimal similarScore;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
