package com.aiops.module.monitor.dto;

import lombok.Data;

/** agent 心跳，请求体可省略 */
@Data
public class AgentHeartbeatRequest {
    private String version;
}
