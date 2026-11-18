package org.nearslot.appointment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 候补提升异步执行器（FR-3.2）。
 *
 * 设计取舍（docs/standards/coding.md §3：禁止 Executors 快捷方法）：
 * · **有界队列**：提升是次高频操作，队列满时必须让调用方感知，而不是无限堆积 OOM；
 * · **CallerRuns**：队列满时退化为同步执行——宁可让发布事件的那次请求稍慢，
 *   也不能丢提升任务（丢了就等于候补用户被永久遗忘）；
 * · 命名线程：便于线上排障（jstack 一眼看出是谁在跑）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 候补提升线程池。 */
    @Bean("promotionExecutor")
    public Executor promotionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("promotion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
