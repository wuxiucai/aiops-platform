package com.aiops.demo.order.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单接口：模拟真实业务时序。
 * 持久化：./data/order-db（H2 file），重启数据保留。
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final AtomicLong seq = new AtomicLong(0);
    private final WebClient paymentClient;
    private final JdbcTemplate jdbc;

    public OrderController(@Value("${demo.payment.base-url:http://127.0.0.1:8082}") String paymentBaseUrl,
                           JdbcTemplate jdbcTemplate) {
        this.paymentClient = WebClient.builder().baseUrl(paymentBaseUrl).build();
        this.jdbc = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS orders(" +
            "  order_id  VARCHAR(64) PRIMARY KEY," +
            "  amount    BIGINT," +
            "  status    VARCHAR(16)," +
            "  create_time TIMESTAMP" +
            ")");
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> req) {
        long amount = req == null ? 100L
                : Long.parseLong(req.getOrDefault("amount", "100").toString());
        String orderId = "ORD" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO orders(order_id,amount,status,create_time) VALUES(?,?,?,?)",
                orderId, amount, "PENDING", java.sql.Timestamp.valueOf(now));
        String traceId = UUID.randomUUID().toString().substring(0, 8);

        log.info("订单创建成功: orderId={}, amount={}, traceId={}", orderId, amount, traceId);

        Map<String, Object> payReq = Map.of("orderId", orderId, "amount", amount, "traceId", traceId);
        try {
            Map<String, Object> payResp = paymentClient.post()
                    .uri("/api/pay/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payReq)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            jdbc.update("UPDATE orders SET status=? WHERE order_id=?", "PAID", orderId);
            log.info("支付回调完成: orderId={}, payResp={}", orderId, payResp);
            return Map.of("code", 200, "orderId", orderId, "status", "PAID",
                    "traceId", traceId, "payResp", payResp);
        } catch (Exception e) {
            jdbc.update("UPDATE orders SET status=? WHERE order_id=?", "PAY_FAIL", orderId);
            log.error("支付回调失败: orderId={}, err={}", orderId, e.getMessage());
            return Map.of("code", 500, "orderId", orderId, "status", "PAY_FAIL",
                    "traceId", traceId, "error", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, amount, status, create_time FROM orders WHERE order_id=?", id);
        if (rows.isEmpty()) {
            return Map.of("code", 404, "msg", "order not found: " + id);
        }
        Map<String, Object> r = rows.get(0);
        String ct = r.get("create_time") == null ? "" :
                ((java.sql.Timestamp) r.get("create_time")).toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return Map.of("code", 200, "order", Map.of(
                "orderId", r.get("order_id"),
                "amount", r.get("amount"),
                "status", r.get("status"),
                "createTime", ct));
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(value = "ms", defaultValue = "3000") long ms)
            throws InterruptedException {
        log.warn("触发慢调用模拟: ms={}", ms);
        Thread.sleep(ms);
        return Map.of("code", 200, "sleptMs", ms);
    }
}
