package com.aiops.demo.order.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 故障注入端点（毕设 M4 / P4-4 演示）：
 *   POST /demo/fault/cpu-burn?seconds=30&threads=4
 *   POST /demo/fault/slow?ms=2000&path=/api/order/create   以及 POST /demo/fault/slow/stop
 *   POST /demo/fault/error-log?pattern=...
 *   POST /demo/fault/jvm-stress?mb=512&seconds=60
 *
 * 所有端点立即返回（不阻塞 HTTP 线程），重复调用幂等——新调用会先停止/释放旧任务。
 */
@RestController
@RequestMapping("/demo/fault")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FaultController {

    private static final Logger log = LoggerFactory.getLogger(FaultController.class);

    /** error-log 用的 logger 名必须是业务语义上的 Service，让 Drain 归并 demonstration 更真实 */
    private static final String BIZ_LOGGER_NAME = "com.aiops.demo.order.service.OrderService";
    private static final Logger bizLog = LoggerFactory.getLogger(BIZ_LOGGER_NAME);

    /** 上限守卫 */
    private static final int MAX_CPU_THREADS = 16;
    private static final long MAX_SLOW_MS = 10_000L;
    private static final int MAX_ERROR_LOG_COUNT = 500;
    private static final int MAX_JVM_MB = 2048;
    private static final int JVM_BLOCK_MB = 8;

    private final FaultState state;

    public FaultController(FaultState state) {
        this.state = state;
    }

    /* ==================== (a) cpu-burn ==================== */
    @PostMapping("/cpu-burn")
    public Map<String, Object> cpuBurn(
            @RequestParam(value = "seconds", defaultValue = "30") long seconds,
            @RequestParam(value = "threads", defaultValue = "4") int threads) {

        if (seconds <= 0) seconds = 30;
        if (threads <= 0) threads = 4;
        if (threads > MAX_CPU_THREADS) threads = MAX_CPU_THREADS;

        // 重复调用：先停掉旧的
        stopCpuBurnSilently();

        state.resetCpuBurnStop();
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        state.setCpuBurnDeadline(deadline);

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                double sink = 0;
                while (!state.isCpuBurnStop() && System.currentTimeMillis() < state.getCpuBurnDeadline()) {
                    sink += Math.sqrt(Math.random());
                }
                if (sink == Double.MIN_VALUE) { // 防止 JIT 优化掉
                    log.trace("cpu-burn sink={}", sink);
                }
            }, "fault-cpu-burn-" + i);
            t.setDaemon(true);
            workers[i] = t;
        }
        state.setCpuBurnThreads(workers);
        state.tryStartCpuBurn();
        for (Thread t : workers) t.start();

        // 到点自动收尾（running 标志位回落，幂等关键）
        Thread cleaner = new Thread(() -> {
            long sleep = state.getCpuBurnDeadline() - System.currentTimeMillis();
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            state.cpuBurnFinished();
        }, "fault-cpu-burn-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();

        log.warn("[FAULT-INJECT] cpu-burn started: seconds={}, threads={}, deadline={}",
                seconds, threads, deadline);

        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("target", "cpu-burn");
        r.put("seconds", seconds);
        r.put("threads", threads);
        r.put("note", "已注入，峰值 " + seconds + "s 后自动退出");
        return r;
    }

    private void stopCpuBurnSilently() {
        if (state.isCpuBurnRunning()) {
            state.stopCpuBurnFlag();
            state.cpuBurnFinished();
        }
    }

    /* ==================== (b) slow ==================== */
    @PostMapping("/slow")
    public Map<String, Object> slow(
            @RequestParam(value = "ms", defaultValue = "2000") long ms,
            @RequestParam(value = "path", defaultValue = "/api/order/create") String path) {

        if (ms <= 0) ms = 2000;
        if (ms > MAX_SLOW_MS) ms = MAX_SLOW_MS;
        if (path == null || path.isEmpty()) path = "/api/order/create";

        state.enableSlow(path, ms);
        log.warn("[FAULT-INJECT] slow enabled: path={}, ms={}", path, ms);

        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("target", "slow");
        r.put("ms", ms);
        r.put("path", path);
        r.put("note", "该路径请求会持续变慢");
        return r;
    }

    @PostMapping("/slow/stop")
    public Map<String, Object> slowStop() {
        state.disableSlow();
        log.warn("[FAULT-INJECT] slow stopped");
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("target", "slow");
        r.put("note", "已解除");
        return r;
    }

    /* ==================== (c) error-log ==================== */
    @PostMapping("/error-log")
    public Map<String, Object> errorLog(
            @RequestParam(value = "pattern", defaultValue = "熔断器开启") String pattern,
            @RequestParam(value = "count", defaultValue = "100") int count) {

        if (pattern == null || pattern.isEmpty()) pattern = "熔断器开启";
        if (count <= 0) count = 100;
        if (count > MAX_ERROR_LOG_COUNT) count = MAX_ERROR_LOG_COUNT;

        for (int i = 0; i < count; i++) {
            String traceId = UUID.randomUUID().toString();
            int amount = ThreadLocalRandom.current().nextInt(1, 1001);
            bizLog.error("[FAULT-INJECT] 业务异常: {}, traceId={}, amount={}", pattern, traceId, amount);
        }
        log.warn("[FAULT-INJECT] error-log fired: pattern={}, count={}", pattern, count);

        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("target", "error-log");
        r.put("pattern", pattern);
        r.put("count", count);
        return r;
    }

    /* ==================== (d) jvm-stress ==================== */
    @PostMapping("/jvm-stress")
    public Map<String, Object> jvmStress(
            @RequestParam(value = "mb", defaultValue = "512") int mb,
            @RequestParam(value = "seconds", defaultValue = "60") long seconds) {

        if (mb <= 0) mb = 512;
        if (mb > MAX_JVM_MB) mb = MAX_JVM_MB;
        if (seconds <= 0) seconds = 60;

        // 重复调用：先 release 旧内存
        state.getHeld().clear();

        // 累计上限 = 2 * mb（红线）
        long capBytes = 2L * mb * 1024L * 1024L;
        state.setJvmTotalBytesCap(capBytes);

        long targetBytes = (long) mb * 1024L * 1024L;
        long blockBytes = (long) JVM_BLOCK_MB * 1024L * 1024L;
        long allocated = 0;
        while (allocated < targetBytes && allocated + blockBytes <= capBytes) {
            state.getHeld().add(new byte[(int) blockBytes]);
            allocated += blockBytes;
        }

        final long releaseAt = System.currentTimeMillis() + seconds * 1000L;
        state.setJvmReleaseAtMillis(releaseAt);

        Thread releaser = new Thread(() -> {
            long sleep = releaseAt - System.currentTimeMillis();
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            state.getHeld().clear();
            log.warn("[FAULT-INJECT] jvm-stress released");
        }, "fault-jvm-stress-releaser");
        releaser.setDaemon(true);
        releaser.start();

        log.warn("[FAULT-INJECT] jvm-stress allocated: mb={}, seconds={}, blocks={}",
                mb, seconds, state.getHeld().size());

        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("target", "jvm-stress");
        r.put("mb", mb);
        r.put("seconds", seconds);
        r.put("blocks", state.getHeld().size());
        r.put("note", "约 10s 内平台 jvm.heap.usage 会上升");
        return r;
    }

    /* ==================== 状态查询（便于演示） ==================== */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> r = new HashMap<>();
        r.put("cpuBurnRunning", state.isCpuBurnRunning());
        r.put("slowEnabled", state.isSlowEnabled());
        r.put("slowPath", state.getSlowPath());
        r.put("slowMs", state.getSlowMs());
        r.put("jvmHeldBlocks", state.getHeld().size());
        r.put("jvmReleaseAtMillis", state.getJvmReleaseAtMillis());
        return r;
    }
}
