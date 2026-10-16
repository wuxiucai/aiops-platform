package com.aiops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关：aiops.scheduling.enabled，默认开启。
 * 与生产实例连同一个库的临时实例（接口回归、集成测试）应关闭，
 * 否则采集 / 告警 / 日志模板任务会双写同一批数据。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "aiops.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
