package com.aiops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池定义（§10.1 必须的四个）。
 * 线程名前缀 aiops-<name>-；LLM 用 Abort 拒绝（失败立即可见），其他用 CallerRuns。
 */
@Configuration
public class AsyncConfig {

    @Bean("aiopsCollectExecutor")
    public ThreadPoolTaskExecutor collectExecutor() {
        return build("collect", 4, 8, 200, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean("aiopsDetectExecutor")
    public ThreadPoolTaskExecutor detectExecutor() {
        return build("detect", 2, 4, 100, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean("aiopsLlmExecutor")
    public ThreadPoolTaskExecutor llmExecutor() {
        return build("llm", 2, 4, 50, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean("aiopsEsExecutor")
    public ThreadPoolTaskExecutor esExecutor() {
        return build("es", 2, 4, 100, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ThreadPoolTaskExecutor build(String name, int core, int max, int queue,
                                         java.util.concurrent.RejectedExecutionHandler policy) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("aiops-" + name + "-");
        executor.setRejectedExecutionHandler(policy);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
