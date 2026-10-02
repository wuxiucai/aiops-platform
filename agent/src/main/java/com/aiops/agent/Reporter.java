package com.aiops.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向平台 POST metric + heartbeat.
 */
@Component
public class Reporter {

    private static final Logger log = LoggerFactory.getLogger(Reporter.class);

    private final AgentConfig cfg;
    private final ObjectMapper om;
    private final WebClient webClient;

    public Reporter(AgentConfig cfg) {
        this.cfg = cfg;
        this.om = new ObjectMapper().registerModule(new JavaTimeModule());
        this.webClient = WebClient.builder()
                .baseUrl(cfg.getPlatformUrl())
                .build();
    }

    /** 上报一批指标；返回平台 accepted 数量 */
    public int reportMetrics(List<Map<String, Object>> metrics) {
        if (metrics == null || metrics.isEmpty()) return 0;
        try {
            String body = om.writeValueAsString(metrics);
            String resp = webClient.post()
                    .uri("/api/agent/metric")
                    .header("X-Agent-Key", cfg.getAgentKey())
                    .header("X-Agent-Version", cfg.getVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            var root = om.readTree(resp);
            int accepted = root.path("data").path("accepted").asInt(0);
            log.info("[agent] metric accepted={} sent={}", accepted, metrics.size());
            return accepted;
        } catch (Exception e) {
            log.warn("[agent] metric push fail: {}", e.getMessage());
            return 0;
        }
    }

    /** 心跳，含 version */
    public void heartbeat() {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("version", cfg.getVersion());
            String resp = webClient.post()
                    .uri("/api/agent/heartbeat")
                    .header("X-Agent-Key", cfg.getAgentKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(om.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            log.debug("[agent] heartbeat ok: {}", resp);
        } catch (Exception e) {
            log.warn("[agent] heartbeat fail: {}", e.getMessage());
        }
    }
}
