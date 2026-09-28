package com.aiops.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示订单服务（端口 8081）。
 * 对外：order/create 下单、order/{id} 查询、order/slow 慢调用；
 * 内部：下单成功后通过 WebClient 调用 payment-service /api/pay/notify。
 */
@SpringBootApplication
public class DemoOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoOrderApplication.class, args);
    }
}
