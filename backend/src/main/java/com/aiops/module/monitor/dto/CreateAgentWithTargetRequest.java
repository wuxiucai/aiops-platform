package com.aiops.module.monitor.dto;

import lombok.Data;

/** 新建监控对象并为其创建 agent（Agent.vue「+ 新建主机」） */
@Data
public class CreateAgentWithTargetRequest {
    /** 必填 */
    private String name;
    /** 必填 */
    private String ip;
    /** host / service，默认 host */
    private String targetType;
    private String os;
    private String logServiceName;
    private String description;
    private Long groupId;
}
