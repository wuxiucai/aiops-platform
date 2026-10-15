package com.aiops.module.incident.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 追加事件时间线（前端以下划线字段 event_type 提交） */
@Data
public class TimelineAppendRequest {
    @JsonProperty("event_type")
    private String eventType;
    private String description;
}
