package com.aiops.module.monitor.dto;

import lombok.Data;

/** 为已有监控对象新建 agent */
@Data
public class CreateAgentRequest {
    private Long targetId;
}
