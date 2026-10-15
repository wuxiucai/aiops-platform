package com.aiops.module.alert.dto;

import lombok.Data;

/** 规则历史回放时间窗，"yyyy-MM-dd HH:mm:ss" 或 ISO；均为空时默认最近 24 小时 */
@Data
public class DryRunRequest {
    private String startTime;
    private String endTime;
}
