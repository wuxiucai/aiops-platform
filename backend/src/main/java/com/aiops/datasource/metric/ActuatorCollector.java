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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actuator/Micrometer 指标采集器：抓取演示服务暴露的 /actuator/metrics。
 * 仅支持 service 类型目标（ip:port 指向演示服务的 actuator 端口）。
 *
 * 关键修复：app.qps 之前是 httpMetrics.count / 60.0，
 * 但 http.server.requests COUNT 是「JVM 启动至今」的累计值，
 * 运行 1 天后会被除以 60 然后虚高约 1440 倍。
 * 改成「差分」：保留上次采集的 (count, ts)，本次差值 / 时间差 = 真实 QPS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorCollector implements MetricCollector {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** targetId → 上次采集的 (count, epochMillis)。ConcurrentHashMap 保证多线程扫描下安全。 */
    private final Map<Long, HttpMetricsSnapshot> prevSnapshot = new ConcurrentHashMap<>();

    private record HttpMetricsSnapshot(long count, long epochMillis) {}

    @Override
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys) {
        throw new UnsupportedOperationException("请使用 collect(targetId, metricKeys, host, port)");
    }

    private static class HttpMetrics {
        long count;
        double totalTimeSec;
    }

    /** 按目标地址采集 actuator 指标 */
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys, String host, int port) {
        List<MetricPoint> points = new ArrayList<>();
        WebClient client = webClientBuilder.baseUrl("http://" + host + ":" + port).build();
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
                            long nowMs = System.currentTimeMillis();
                            if (key.equals("app.qps")) {
                                // 差分：本次 count - 上次 count，除以真实时间差
                                HttpMetricsSnapshot prev = prevSnapshot.put(targetId,
                                        new HttpMetricsSnapshot(httpMetrics.count, nowMs));
                                if (prev != null && nowMs > prev.epochMillis()) {
                                    long deltaCount = httpMetrics.count - prev.count();
                                    // 进程重启则 count 归零，保守不发出点
                                    if (deltaCount >= 0) {
                                        double qps = deltaCount * 1000.0 / (nowMs - prev.epochMillis());
                                        points.add(new MetricPoint("app.qps", qps));
                                    }
                                }
                                // prev == null：第一次采集，没有 base，跳过本次
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
