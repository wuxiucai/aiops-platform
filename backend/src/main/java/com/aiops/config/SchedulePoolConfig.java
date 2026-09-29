package com.aiops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度线程池：默认只有 1 个调度线程时，任一 Job 阻塞/长耗时会堆住其它 Job。
 * 任务书 §5.5 要求 metric/alert/log_template/log_detect 并行——池子给到 4。
 */
@Configuration
public class SchedulePoolConfig {

    @Bean
    public ThreadPoolTaskScheduler aiopsTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("aiops-sched-");
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
