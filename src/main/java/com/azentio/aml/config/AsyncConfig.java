package com.azentio.aml.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor backing asynchronous detection.
 *
 * <p>The queue is bounded and the rejection policy runs the work on the calling thread: under a
 * burst, ingestion slows down rather than silently dropping transactions, which would mean lost
 * alerts. Concurrency safety for the alerts themselves comes from the unique {@code dedupe_key} and
 * the optimistic-lock version on {@code Alert}, not from serialising this pool.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(SentinelProperties.class)
public class AsyncConfig {

    public static final String DETECTION_EXECUTOR = "detectionExecutor";

    @Bean(name = DETECTION_EXECUTOR)
    public Executor detectionExecutor(SentinelProperties properties) {
        int workers = properties.getDetection().getWorkerThreads();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers * 2);
        executor.setQueueCapacity(workers * 250);
        executor.setThreadNamePrefix("sentinel-detect-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
