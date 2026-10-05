package com.aiops.datasource.metric;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S4 Prometheus text-format 采集器。
 * 拉取 http://host:port/metrics 文本， 解析 prometheus exposition format, 提取我们关注的指标。
 *
 * 目标类型: target_type='prometheus'
 * 配置: monitor_target.target_url = http://x.x.x.x:9100/metrics
 *
 * 默认映射 （术语 node_exporter):
 *   - node_cpu_seconds_total      -> cpu.usage     (按 rate 派生, 采集间隔由调用方决定; 此处简化为瞬时空闲率）
 *   - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes  -> mem.usage
 *   - node_filesystem_avail_bytes / node_filesystem_size_bytes     -> disk.usage
 *   - node_load1                  -> cpu.load
 *   - node_network_receive_bytes_total / node_network_transmit_bytes_total (5m rate 不直观， 简化为瞬时值）
 *
 * 实现策略： 全量 pull 一次文本， 然后用 map 查询， 不重复 HTTP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrometheusCollector implements MetricCollector {

    private final WebClient.Builder webClientBuilder;

    /** http 拉取超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Override
    public boolean supports(String targetType) {
        return "prometheus".equalsIgnoreCase(targetType);
    }

    @Override
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys) {
        // 此签名缺 url, 走 4 参版本
        throw new UnsupportedOperationException("请使用 collect(targetId, metricKeys, url)");
    }

    /**
     * 全量拉取 + 映射到我们关心的 metricKeys
     */
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys, String url) {
        List<MetricPoint> out = new ArrayList<>();
        if (url == null || url.isBlank()) {
            log.warn("[prom] target={} 未配置 target_url", targetId);
            return out;
        }
        String text;
        try {
            text = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("[prom] 拉取失败 target={} url={} err={}", targetId, url, e.getMessage());
            return out;
        }
        if (text == null || text.isBlank()) return out;

        Map<String, Double> raw = parseExposition(text);
        for (String key : metricKeys) {
            Double v = mapMetric(key, raw);
            if (v != null) {
                out.add(new MetricPoint(key, v));
            }
        }
        return out;
    }

    /** 把 prometheus 原始指标 map 转换为我们平台的 metricKey 含义 */
    private Double mapMetric(String key, Map<String, Double> raw) {
        return switch (key) {
            case "cpu.usage" -> {
                // (1 - idle/total) * 100. 瞬时值，非 rate。生产应配 rate 查询，毕设简化。
                Double idle = sumByMetricPrefix(raw, "node_cpu_seconds_total", "mode=\"idle\"");
                Double total = sumAllCpuModes(raw);
                if (idle == null || total == null || total <= 0) yield null;
                yield (1.0 - idle / total) * 100.0;
            }
            case "mem.usage" -> {
                Double avail = raw.getOrDefault("node_memory_MemAvailable_bytes",
                        raw.get("node_memory_MemFree_bytes"));
                Double total = raw.get("node_memory_MemTotal_bytes");
                if (avail == null || total == null || total <= 0) yield null;
                yield (1.0 - avail / total) * 100.0;
            }
            case "disk.usage" -> {
                // 根分区 / 全部求和近似
                Double avail = sumByMetricPrefix(raw, "node_filesystem_avail_bytes", null);
                Double total = sumByMetricPrefix(raw, "node_filesystem_size_bytes", null);
                if (avail == null || total == null || total <= 0) yield null;
                yield (1.0 - avail / total) * 100.0;
            }
            case "cpu.load" -> raw.get("node_load1");
            case "mem.used" -> {
                Double avail = raw.getOrDefault("node_memory_MemAvailable_bytes",
                        raw.get("node_memory_MemFree_bytes"));
                Double total = raw.get("node_memory_MemTotal_bytes");
                if (avail == null || total == null) yield null;
                yield (total - avail) / 1024.0 / 1024.0 / 1024.0; // GB
            }
            case "jvm.heap.usage" -> {
                Double used = sumByMetricPrefix(raw, "jvm_memory_used_bytes", "area=\"heap\"");
                Double max  = sumByMetricPrefix(raw, "jvm_memory_max_bytes",  "area=\"heap\"");
                if (used == null || max == null || max <= 0) yield null;
                yield used * 100.0 / max;
            }
            case "jvm.thread.count" -> sumByMetricPrefix(raw, "jvm_threads_live_threads", null);
            case "process.cpu.usage" -> sumByMetricPrefix(raw, "process_cpu_usage", null);
            case "http.server.requests.count" -> sumByMetricPrefix(raw, "http_server_requests_seconds_count", null);
            case "system.cpu.usage" -> sumByMetricPrefix(raw, "system_cpu_usage", null);
            default -> {
                // 直接当 prometheus 指标名 (无 label)
                Double v = raw.get(key);
                if (v == null) v = sumByMetricPrefix(raw, key, null);
                yield v;
            }
        };
    }

    /** 累加 node_cpu_seconds_total 全部 mode， 前提 cpu label 不重复 （多核会按核累加， 但占比仍对） */
    private Double sumAllCpuModes(Map<String, Double> raw) {
        double total = 0;
        boolean found = false;
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getKey().startsWith("node_cpu_seconds_total")) {
                total += e.getValue();
                found = true;
            }
        }
        return found ? total : null;
    }

    /** 累加指定 prefix 下， 含 label 子串的样本值 */
    private Double sumByMetricPrefix(Map<String, Double> raw, String prefix, String labelContains) {
        double total = 0;
        boolean found = false;
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            if (labelContains != null && !e.getKey().contains(labelContains)) continue;
            total += e.getValue();
            found = true;
        }
        return found ? total : null;
    }

    /**
     * 解析 prometheus exposition 文本， 把每行转 "metric_name{labels}" -> value。
     * 忽略 # HELP/# TYPE 注释和空行； 简单按空白 split, 不处理引号转义， 毕设规模够用。
     */
    Map<String, Double> parseExposition(String text) {
        Map<String, Double> out = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int sp = line.lastIndexOf(' ');
            if (sp < 0) continue;
            String name = line.substring(0, sp).trim();
            String valStr = line.substring(sp + 1).trim();
            try {
                double v = Double.parseDouble(valStr);
                out.put(name, v);
            } catch (NumberFormatException ignored) {
                /* NaN / +Inf 等跳过 */
            }
        }
        return out;
    }
}
