package com.aiops.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 启动配置（平台下发时填充）。
 */
@Component
public class AgentConfig {

    @Value("${platform.url}")
    private String platformUrl;

    @Value("${agent.key}")
    private String agentKey;

    @Value("${agent.target-id}")
    private Long targetId;

    @Value("${agent.version}")
    private String version;

    @Value("${agent.collect-interval-sec}")
    private int collectIntervalSec;

    @Value("${agent.heartbeat-interval-sec}")
    private int heartbeatIntervalSec;

    public String getPlatformUrl() { return platformUrl; }
    public String getAgentKey() { return agentKey; }
    public Long getTargetId() { return targetId; }
    public String getVersion() { return version; }
    public int getCollectIntervalSec() { return collectIntervalSec; }
    public int getHeartbeatIntervalSec() { return heartbeatIntervalSec; }
}
