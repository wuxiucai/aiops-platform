package com.aiops.agent;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OSHI-based 采集器（指标 key 与平台 monitor_target 定义一致）.
 * 返回 [{metricKey, value, timestamp}].
 */
@Component
public class OshiCollector {

    private static final Logger log = LoggerFactory.getLogger(OshiCollector.class);

    private final SystemInfo systemInfo = new SystemInfo();
    private long[] prevCpuTicks;
    private long prevNetRx = 0;
    private long prevNetTx = 0;

    /** 采集一批指标 */
    public List<Map<String, Object>> collect() {
        List<Map<String, Object>> out = new ArrayList<>();
        HardwareAbstractionLayer hal = systemInfo.getHardware();

        double cpu = collectCpu(hal.getProcessor());
        if (cpu >= 0) out.add(metric("cpu.usage", cpu));

        GlobalMemory mem = hal.getMemory();
        if (mem != null && mem.getTotal() > 0) {
            double memPct = (double) (mem.getTotal() - mem.getAvailable()) * 100.0 / mem.getTotal();
            out.add(metric("mem.usage", round2(memPct)));
        }

        long total = 0, used = 0;
        for (oshi.software.os.OSFileStore fs : systemInfo.getOperatingSystem().getFileSystem().getFileStores()) {
            total += fs.getTotalSpace();
            used += fs.getTotalSpace() - fs.getFreeSpace();
        }
        if (total > 0) {
            double diskPct = (double) used * 100.0 / total;
            out.add(metric("disk.usage", round2(diskPct)));
        }

        long rx = 0, tx = 0;
        for (NetworkIF nif : hal.getNetworkIFs()) {
            rx += nif.getBytesRecv();
            tx += nif.getBytesSent();
        }
        if (prevNetRx > 0 && rx >= prevNetRx) {
            long drx = rx - prevNetRx;
            long dtx = tx - prevNetTx;
            out.add(metric("net.rx.kb.per.sec", round2(drx / 1024.0)));
            out.add(metric("net.tx.kb.per.sec", round2(dtx / 1024.0)));
        }
        prevNetRx = rx;
        prevNetTx = tx;

        try {
            double[] loads = hal.getProcessor().getSystemLoadAverage(1);
            if (loads != null && loads[0] >= 0) out.add(metric("cpu.load", round2(loads[0])));
        } catch (Exception ignore) { }

        return out;
    }

    private double collectCpu(CentralProcessor p) {
        if (p == null) return -1;
        long[] ticks = p.getSystemCpuLoadTicks();
        if (ticks == null) return -1;
        if (prevCpuTicks == null) {
            prevCpuTicks = ticks;
            return -1;
        }
        long user = ticks[CentralProcessor.TickType.USER.getIndex()] - prevCpuTicks[CentralProcessor.TickType.USER.getIndex()];
        long nice = ticks[CentralProcessor.TickType.NICE.getIndex()] - prevCpuTicks[CentralProcessor.TickType.NICE.getIndex()];
        long system = ticks[CentralProcessor.TickType.SYSTEM.getIndex()] - prevCpuTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] - prevCpuTicks[CentralProcessor.TickType.IDLE.getIndex()];
        long iowait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()] - prevCpuTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()] - prevCpuTicks[CentralProcessor.TickType.IRQ.getIndex()];
        long softirq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()] - prevCpuTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()] - prevCpuTicks[CentralProcessor.TickType.STEAL.getIndex()];
        prevCpuTicks = ticks;
        long total = user + nice + system + idle + iowait + irq + softirq + steal;
        if (total <= 0) return -1;
        double busyPct = (double) (user + nice + system + irq + softirq) * 100.0 / total;
        return round2(busyPct);
    }

    private Map<String, Object> metric(String key, double value) {
        Map<String, Object> m = new HashMap<>();
        m.put("metricKey", key);
        m.put("value", value);
        m.put("timestamp", LocalDateTime.now());
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
