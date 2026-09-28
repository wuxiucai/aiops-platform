package com.aiops.demo.order.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单接口：模拟真实业务时序。
 * <p>
 *   POST /api/order/create    下单（生成 orderId、写 INFO 日志、调用 payment-service 通知）
 *   GET  /api/order/{id}      查询订单状态
 *   GET  /api/order/slow?ms=  慢调用，用于触发慢响应告警链路
 * </p>
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    /** 内存存储，毕设够用 */
    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    private final WebClient paymentClient;

    public OrderController(@Value("${demo.payment.base-url:http://127.0.0.1:8082}") String paymentBaseUrl) {
        this.paymentClient = WebClient.builder().baseUrl(paymentBaseUrl).build();
    }

    public record OrderRecord(String orderId, long amount, String status, LocalDateTime createTime) {}

    /** 下单：立即写 INFO 日志（用于模板提取），并调用 payment-service 形成真实调用链 */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> req) {
        long amount = req == null ? 100L
                : Long.parseLong(req.getOrDefault("amount", "100").toString());
        String orderId = "ORD" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        OrderRecord record = new OrderRecord(orderId, amount, "PENDING", LocalDateTime.now());
        orders.put(orderId, record);
        String traceId = UUID.randomUUID().toString().substring(0, 8);

        log.info("订单创建成功: orderId={}, amount={}, traceId={}", orderId, amount, traceId);

        // 内部调用 payment-service（拓扑真实感来源）
        Map<String, Object> payReq = Map.of(
                "orderId", orderId,
                "amount", amount,
                "traceId", traceId);
        try {
            Map<String, Object> payResp = paymentClient.post()
                    .uri("/api/pay/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payReq)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            orders.put(orderId, new OrderRecord(orderId, amount, "PAID", record.createTime()));
            log.info("支付回调完成: orderId={}, payResp={}", orderId, payResp);
            return Map.of(
                    "code", 200,
                    "orderId", orderId,
                    "status", "PAID",
                    "traceId", traceId,
                    "payResp", payResp);
        } catch (Exception e) {
            orders.put(orderId, new OrderRecord(orderId, amount, "PAY_FAIL", record.createTime()));
            log.error("支付回调失败: orderId={}, err={}", orderId, e.getMessage());
            return Map.of(
                    "code", 500,
                    "orderId", orderId,
                    "status", "PAY_FAIL",
                    "traceId", traceId,
                    "error", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /** 查询订单 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") String id) {
        OrderRecord r = orders.get(id);
        if (r == null) {
            return Map.of("code", 404, "msg", "order not found: " + id);
        }
        return Map.of("code", 200, "order", Map.of(
                "orderId", r.orderId(),
                "amount", r.amount(),
                "status", r.status(),
                "createTime", r.createTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
    }

    /** 慢调用模拟：返回内容包含本次注入的延迟参数，便于告警页查看故障描述 */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(value = "ms", defaultValue = "3000") long ms)
            throws InterruptedException {
        log.warn("触发慢调用模拟: ms={}", ms);
        Thread.sleep(ms);
        return Map.of("code", 200, "sleptMs", ms);
    }
}
