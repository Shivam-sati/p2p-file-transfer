package com.filetransfer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Separate thread pools for:
 * - mergeExecutor  : long-running file assembly jobs (small pool, large queue)
 * - chunkExecutor  : concurrent chunk writes (larger pool, bounded queue)
 *
 * Keeping them separate prevents chunk uploads from being starved by merge jobs.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Value("${app.async.merge-pool-size:4}")
    private int mergePoolSize;

    @Value("${app.async.chunk-pool-size:8}")
    private int chunkPoolSize;

    @Bean(name = "mergeExecutor")
    public Executor mergeExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(mergePoolSize);
        exec.setMaxPoolSize(mergePoolSize);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("merge-");
        exec.setWaitForTasksToCompleteOnShutdown(true);  // finish merges gracefully
        exec.setAwaitTerminationSeconds(120);
        exec.initialize();
        return exec;
    }

    @Bean(name = "chunkExecutor")
    public Executor chunkExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(chunkPoolSize);
        exec.setMaxPoolSize(chunkPoolSize * 2);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("chunk-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }
}