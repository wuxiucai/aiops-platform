package com.aiops.demo.payment.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付接口：被 order-service 调用，产生真实调用链与日志。
 */
@RestController
@RequestMapping("/api/pay")
public class PayController {

    private static final Logger log = LoggerFactory.getLogger(PayController.class);

    private final Map<String, PayRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    public record PayRecord(String orderId, long amount, String payId, String status, LocalDateTime time) {}

    /** 接收支付通知：生成 payId、写 INFO 日志、返回状态 */
    @PostMapping("/notify")
    public Map<String, Object> notify(@RequestBody Map<String, Object> req) {
        String orderId = String.valueOf(req.getOrDefault("orderId", ""));
        long amount = Long.parseLong(String.valueOf(req.getOrDefault("amount", "0")));
        String traceId = String.valueOf(req.getOrDefault("traceId", ""));
        String payId = "PAY" + System.currentTimeMillis() + "-" + seq.incrementAndGet();

        // 极小额模拟风控失败：amount<=0 时返回 failure（便于测试异常路径）
        if (amount <= 0) {
            log.error("支付拒绝: orderId={}, amount={}, reason=INVALID_AMOUNT, traceId={}", orderId, amount, traceId);
            return Map.of("code", 400, "payId", payId, "status", "FAIL", "reason", "INVALID_AMOUNT", "traceId", traceId);
        }
        PayRecord r = new PayRecord(orderId, amount, payId, "SUCCESS", LocalDateTime.now());
        records.put(orderId, r);
        log.info("支付成功: orderId={}, payId={}, amount={}, traceId={}", orderId, payId, amount, traceId);
        return Map.of("code", 200, "payId", payId, "status", "SUCCESS", "traceId", traceId);
    }

    /** 查询支付状态 */
    @GetMapping("/status/{orderId}")
    public Map<String, Object> status(@PathVariable("orderId") String orderId) {
        PayRecord r = records.get(orderId);
        if (r == null) {
            return Map.of("code", 404, "msg", "no pay record for orderId=" + orderId);
        }
        return Map.of("code", 200, "orderId", r.orderId(), "payId", r.payId(),
                "amount", r.amount(), "status", r.status(), "time", r.time().toString());
    }
}
