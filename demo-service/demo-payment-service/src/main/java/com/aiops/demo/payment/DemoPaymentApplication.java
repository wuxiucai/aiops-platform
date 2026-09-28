package com.aiops.demo.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示支付服务（端口 8082）。
 * 对外：/api/pay/notify 接收订单支付通知、/api/pay/status/{orderId} 查询状态。
 */
@SpringBootApplication
public class DemoPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoPaymentApplication.class, args);
    }
}
