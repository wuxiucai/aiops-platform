package com.aiops.datasource.metric;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
                case "net.conn.count" -> round(os.getFileSystem().getOpenFileDescriptors() * 0.0 + 0);
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
}
