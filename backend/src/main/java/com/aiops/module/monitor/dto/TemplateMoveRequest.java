package com.aiops.module.monitor.dto;

import lombok.Data;

/** 移动模板到分组；groupId 为 null 表示移入未归类 */
@Data
public class TemplateMoveRequest {
    private Long groupId;
}
