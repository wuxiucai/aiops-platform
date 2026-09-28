package com.aiops.demo.payment.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付接口：被 order-service 调用，产生真实调用链与日志。
 * 持久化：./data/payment-db（H2 file），重启数据保留。
 */
@RestController
@RequestMapping("/api/pay")
public class PayController {

    private static final Logger log = LoggerFactory.getLogger(PayController.class);
    private final AtomicLong seq = new AtomicLong(0);
    private final JdbcTemplate jdbc;

    public PayController(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS pay_record(" +
            "  order_id  VARCHAR(64) PRIMARY KEY," +
            "  amount    BIGINT," +
            "  pay_id    VARCHAR(64)," +
            "  status    VARCHAR(16)," +
            "  pay_time  TIMESTAMP" +
            ")");
    }

    @PostMapping("/notify")
    public Map<String, Object> notify(@RequestBody Map<String, Object> req) {
        String orderId = String.valueOf(req.getOrDefault("orderId", ""));
        long amount = Long.parseLong(String.valueOf(req.getOrDefault("amount", "0")));
        String traceId = String.valueOf(req.getOrDefault("traceId", ""));
        String payId = "PAY" + System.currentTimeMillis() + "-" + seq.incrementAndGet();

        if (amount <= 0) {
            log.error("支付拒绝: orderId={}, amount={}, reason=INVALID_AMOUNT, traceId={}", orderId, amount, traceId);
            return Map.of("code", 400, "payId", payId, "status", "FAIL",
                    "reason", "INVALID_AMOUNT", "traceId", traceId);
        }
        jdbc.update("INSERT INTO pay_record(order_id,amount,pay_id,status,pay_time) VALUES(?,?,?,?,?)",
                orderId, amount, payId, "SUCCESS", java.sql.Timestamp.valueOf(LocalDateTime.now()));
        log.info("支付成功: orderId={}, payId={}, amount={}, traceId={}", orderId, payId, amount, traceId);
        return Map.of("code", 200, "payId", payId, "status", "SUCCESS", "traceId", traceId);
    }

    @GetMapping("/status/{orderId}")
    public Map<String, Object> status(@PathVariable("orderId") String orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, amount, pay_id, status, pay_time FROM pay_record WHERE order_id=?", orderId);
        if (rows.isEmpty()) {
            return Map.of("code", 404, "msg", "no pay record for orderId=" + orderId);
        }
        Map<String, Object> r = rows.get(0);
        return Map.of("code", 200, "orderId", r.get("order_id"), "payId", r.get("pay_id"),
                "amount", r.get("amount"), "status", r.get("status"),
                "time", r.get("pay_time") == null ? "" : r.get("pay_time").toString());
    }
}
