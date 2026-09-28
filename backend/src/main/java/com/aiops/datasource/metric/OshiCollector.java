package com.aiops.datasource.metric;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import oshi.software.os.InternetProtocolStats;

import java.util.ArrayList;
import java.util.List;

/**
 * OSHI 本机指标采集器（仅支持本机 host 目标）。
 * 覆盖 v2 §2.5 指标清单中 OS 侧：cpu/mem/disk/net。
 */
@Slf4j
@Component
public class OshiCollector implements MetricCollector {

    private final oshi.SystemInfo systemInfo = new oshi.SystemInfo();
    private final oshi.hardware.CentralProcessor cpu = systemInfo.getHardware().getProcessor();
    private final oshi.hardware.GlobalMemory memory = systemInfo.getHardware().getMemory();
    private final oshi.software.os.OperatingSystem os = systemInfo.getOperatingSystem();

    /** TCP 状态采样（Windows 下用 netstat ESTABLISHED 近似；Linux 用 OSHI 精确值） */
    private final InternetProtocolStats ipStats = os.getInternetProtocolStats();

    /** 上一次 CPU 采样（OSHI 需要 diff 计算） */
    private long[] prevTicks = cpu.getSystemCpuLoadTicks();

    @Override
    public List<MetricPoint> collect(Long targetId, List<String> metricKeys) {
        List<MetricPoint> points = new ArrayList<>();
        long[] newTicks = cpu.getSystemCpuLoadTicks();
        double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
        prevTicks = newTicks;

        for (String key : metricKeys) {
            Double value = switch (key) {
                case "cpu.usage" -> round(cpuLoad);
                case "cpu.load" -> {
                    double[] loads = cpu.getSystemLoadAverage(1);
                    yield round(loads.length > 0 ? loads[0] : 0);
                }
                case "mem.usage" -> {
                    double total = memory.getTotal();
                    yield round((total - memory.getAvailable()) * 100.0 / total);
                }
                case "mem.used" -> round((memory.getTotal() - memory.getAvailable()) / 1024 / 1024);
                case "swap.usage" -> {
                    long total = memory.getVirtualMemory().getSwapTotal();
                    long used = memory.getVirtualMemory().getSwapUsed();
                    yield total == 0 ? 0 : round(used * 100.0 / total);
                }
                case "disk.usage" -> {
                    oshi.software.os.FileSystem fs = os.getFileSystem();
                    double maxUsage = 0;
                    for (oshi.software.os.OSFileStore store : fs.getFileStores()) {
                        long total = store.getTotalSpace();
                        if (total > 0) {
                            double u = store.getUsableSpace() * 1.0 / total;
                            maxUsage = Math.max(maxUsage, 1 - u);
                        }
                    }
                    yield round(maxUsage * 100);
                }
                case "net.conn.count" -> netConnCount();
                default -> null;
            };
            if (value != null) {
                points.add(new MetricPoint(key, value));
            }
        }
        return points;
    }

    @Override
    public boolean supports(String targetType) {
        return "host".equals(targetType);
    }

    private double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /**
     * net.conn.count：优先 OSHI 精确值（Linux）；Windows 下 OSHI 该指标为 0，
     * 降级用 `netstat -an | Select-String ESTABLISHED` 统计近似值（近似值，实际意义是
     * "当今处于 ESTABLISHED 状态的连接数"，已在 metric_definition.description 注明）。
     */
    private double netConnCount() {
        // Linux 下 OSHI 提供精确值，直接用
        try {
            InternetProtocolStats.TcpStats tcp = ipStats.getTCPv4Stats();
            long est = tcp.getConnectionsEstablished();
            if (est > 0) {
                return est;
            }
        } catch (Exception ignored) {
        }
        // Windows 退回 netstat 近似：用 PowerShell 在单进程内完成，避免 cmd pipe 编码问题
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("win")) {
            return 0;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "(netstat -an | Select-String 'ESTABLISHED').Count");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!out.isEmpty() && out.matches("\\d+")) {
                return Double.parseDouble(out);
            }
        } catch (Exception e) {
            log.warn("[Metric] net.conn.count powershell 采样失败: {}", e.getMessage());
        }
        return 0;
    }
}
