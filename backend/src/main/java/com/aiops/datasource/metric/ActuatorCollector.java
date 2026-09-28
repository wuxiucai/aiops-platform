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

    /** http.server.requests 的 measurements 数据缓存（一个 key 用两次时用最近一次抓的） */
    private static class HttpMetrics {
        long count;
        double totalTimeSec;
    }

    /** 按目标地址采集 actuator 指标 */
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys, String host, int port) {
        List<MetricPoint> points = new ArrayList<>();
        WebClient client = webClientBuilder.baseUrl("http://" + host + ":" + port).build();
        // 对 http.server.requests 派生指标（qps / rt.avg / rt.p95）只抓一次，避免重复 HTTP 调用
        HttpMetrics httpMetrics = null;
        for (String key : metricKeys) {
            try {
                switch (key) {
                    case "jvm.heap.usage" -> {
                        double used = readFirstValue(client, "jvm.memory.used", targetId);
                        double max = readFirstValue(client, "jvm.memory.max", targetId);
                        if (max > 0) {
                            points.add(new MetricPoint(key, used * 100.0 / max));
                        }
                    }
                    case "jvm.thread.count" -> {
                        double v = readFirstValue(client, "jvm.threads.live", targetId);
                        points.add(new MetricPoint(key, v));
                    }
                    case "jvm.gc.count" -> {
                        double v = readFirstValue(client, "jvm.gc.pause", "COUNT", targetId);
                        points.add(new MetricPoint(key, v));
                    }
                    case "jvm.gc.time" -> {
                        double vSec = readFirstValue(client, "jvm.gc.pause", "TOTAL_TIME", targetId);
                        points.add(new MetricPoint(key, vSec * 1000));
                    }
                    case "app.qps", "app.rt.avg" -> {
                        if (httpMetrics == null) {
                            httpMetrics = readHttpServerRequests(client, targetId);
                        }
                        if (httpMetrics.count > 0) {
                            if (key.equals("app.qps")) {
                                // 近 60s 累计值折算 QPS（Micrometer 未给原生 rate，简化处理）
                                points.add(new MetricPoint("app.qps", httpMetrics.count / 60.0));
                            } else {
                                double rtMs = httpMetrics.totalTimeSec * 1000.0 / httpMetrics.count;
                                points.add(new MetricPoint("app.rt.avg", rtMs));
                            }
                        }
                    }
                    default -> log.debug("[Metric] actuator 未识别指标: {}", key);
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

    /** 取 actuator 指标的第一个 measurement 值 */
    private double readFirstValue(WebClient client, String metricName, Long targetId) {
        return readFirstValue(client, metricName, null, targetId);
    }

    /** 取 actuator 指标指定 statistic 的 measurement 值 */
    private double readFirstValue(WebClient client, String metricName, String statistic, Long targetId) {
        try {
            String resp = client.get()
                    .uri("/actuator/metrics/" + metricName)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            JsonNode root = objectMapper.readTree(resp);
            JsonNode measurements = root.path("measurements");
            if (measurements.isArray() && !measurements.isEmpty()) {
                for (JsonNode m : measurements) {
                    String stat = m.path("statistic").asText("");
                    if (statistic == null || statistic.equals(stat)) {
                        return m.path("value").asDouble(0);
                    }
                }
                return measurements.get(0).path("value").asDouble(0);
            }
        } catch (Exception e) {
            log.warn("[Metric] 读取 actuator {} 失败: target={}, err={}", metricName, targetId, e.getMessage());
        }
        return 0;
    }

    /** 抓一次 http.server.requests 并解析出 count/totalTimeSec（一个 key 复用） */
    private HttpMetrics readHttpServerRequests(WebClient client, Long targetId) {
        HttpMetrics m = new HttpMetrics();
        try {
            String resp = client.get()
                    .uri("/actuator/metrics/http.server.requests")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            JsonNode root = objectMapper.readTree(resp);
            JsonNode measurements = root.path("measurements");
            if (measurements.isArray()) {
                for (JsonNode item : measurements) {
                    String stat = item.path("statistic").asText("");
                    double v = item.path("value").asDouble(0);
                    if ("COUNT".equals(stat)) {
                        m.count = (long) v;
                    } else if ("TOTAL_TIME".equals(stat)) {
                        m.totalTimeSec = v;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Metric] http.server.requests 读取失败: target={}, err={}", targetId, e.getMessage());
        }
        return m;
    }
}
