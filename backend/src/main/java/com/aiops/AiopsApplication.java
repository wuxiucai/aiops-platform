package com.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能运维告警与日志分析平台 启动类
 */
@EnableScheduling
@SpringBootApplication
public class AiopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiopsApplication.class, args);
    }
}
