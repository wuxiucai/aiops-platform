package com.aiops.demo.payment.common;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 故障注入运行时状态（单例）。
 *   - cpuBurn*   : /demo/fault/cpu-burn
 *   - slow*      : /demo/fault/slow + SlowRequestFilter 读取
 *   - held       : /demo/fault/jvm-stress 持有的 byte[]
 *
 * 所有字段对多线程安全（volatile / Atomic / CopyOnWrite）。
 */
@Component
public class FaultState {

    /* ---------------- cpu-burn ---------------- */
    private final AtomicBoolean cpuBurnRunning = new AtomicBoolean(false);
    private volatile boolean cpuBurnStop = false;
    private volatile long cpuBurnDeadline = 0L;
    private final AtomicReference<Thread[]> cpuBurnThreads = new AtomicReference<>(new Thread[0]);

    /* ---------------- slow ---------------- */
    private final AtomicBoolean slowEnabled = new AtomicBoolean(false);
    private final AtomicLong slowMs = new AtomicLong(0L);
    private final AtomicReference<String> slowPath = new AtomicReference<>("");

    /* ---------------- jvm-stress ---------------- */
    /** 持有的内存块（每块 8MB），强引用，release 时 clear */
    private final List<byte[]> held = new CopyOnWriteArrayList<>();
    private volatile long jvmReleaseAtMillis = 0L;
    private final AtomicLong jvmTotalBytesCap = new AtomicLong(0L);

    /* ================= cpu-burn ================= */
    public boolean tryStartCpuBurn() { return cpuBurnRunning.compareAndSet(false, true); }
    public void stopCpuBurnFlag() { cpuBurnStop = true; }
    public void resetCpuBurnStop() { cpuBurnStop = false; }
    public boolean isCpuBurnStop() { return cpuBurnStop; }
    public void setCpuBurnDeadline(long d) { this.cpuBurnDeadline = d; }
    public long getCpuBurnDeadline() { return cpuBurnDeadline; }
    public void setCpuBurnThreads(Thread[] t) { cpuBurnThreads.set(t); }
    public Thread[] getCpuBurnThreads() { return cpuBurnThreads.get(); }
    public void cpuBurnFinished() { cpuBurnRunning.set(false); }
    public boolean isCpuBurnRunning() { return cpuBurnRunning.get(); }

    /* ================= slow ================= */
    public boolean isSlowEnabled() { return slowEnabled.get(); }
    public void enableSlow(String path, long ms) {
        slowPath.set(path == null ? "" : path);
        slowMs.set(ms);
        slowEnabled.set(true);
    }
    public void disableSlow() {
        slowEnabled.set(false);
        slowMs.set(0L);
        slowPath.set("");
    }
    public long getSlowMs() { return slowMs.get(); }
    public String getSlowPath() { return slowPath.get(); }

    /* ================= jvm-stress ================= */
    public List<byte[]> getHeld() { return held; }
    public void setJvmReleaseAtMillis(long t) { this.jvmReleaseAtMillis = t; }
    public long getJvmReleaseAtMillis() { return jvmReleaseAtMillis; }
    public long getJvmTotalBytesCap() { return jvmTotalBytesCap.get(); }
    public void setJvmTotalBytesCap(long cap) { jvmTotalBytesCap.set(cap); }
}
