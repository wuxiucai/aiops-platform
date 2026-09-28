package com.aiops.datasource.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Actuator/Micrometer 指标采集器：抓取演示服务暴露的 /actuator/metrics。
 * 仅支持 service 类型目标（ip:port 指向演示服务的 actuator 端口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorCollector implements MetricCollector {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys) {
        // 由调用方（CollectService）把 target 的 ip:port 传入；此处通过 ThreadLocal 简化传递
        throw new UnsupportedOperationException("请使用 collect(targetId, metricKeys, host, port)");
    }

    /** 按目标地址采集 actuator 指标 */
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys, String host, int port) {
        List<MetricPoint> points = new ArrayList<>();
        WebClient client = webClientBuilder.baseUrl("http://" + host + ":" + port).build();
        for (String key : metricKeys) {
            String metricName = mapToActuator(key);
            if (metricName == null) {
                continue;
            }
            try {
                String resp = client.get()
                        .uri("/actuator/metrics/" + metricName)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
                JsonNode root = objectMapper.readTree(resp);
                JsonNode measurements = root.path("measurements");
                if (measurements.isArray() && !measurements.isEmpty()) {
                    double value = measurements.get(0).path("value").asDouble();
                    points.add(new MetricPoint(key, value));
                }
            } catch (Exception e) {
                log.warn("[Metric] actuator 采集失败: target={}, metric={}, err={}",
                        targetId, key, e.getMessage());
            }
        }
        return points;
    }

    @Override
    public boolean supports(String targetType) {
        return "service".equals(targetType);
    }

    /** 平台指标 key → actuator 指标名 */
    private String mapToActuator(String key) {
        return switch (key) {
            case "jvm.heap.usage" -> "jvm.memory.used";
            case "jvm.thread.count" -> "jvm.threads.live";
            case "app.qps" -> "http.server.requests.count";
            case "app.rt.avg" -> "http.server.requests";
            default -> null;
        };
    }
}
