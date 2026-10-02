package com.aiops.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AIOps agent — 纯 Push 模式
 * - 定时 OSHI 采集 cpu.usage / mem.usage / disk.usage / net.rx/tx
 * - HTTP POST <platform>/api/agent/metric (X-Agent-Key)
 * - 定时心跳 <platform>/api/agent/heartbeat (X-Agent-Key)
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
        System.out.println("===== aiops-agent started. See [agent] scheduler log for platform/target info.");
    }
}
