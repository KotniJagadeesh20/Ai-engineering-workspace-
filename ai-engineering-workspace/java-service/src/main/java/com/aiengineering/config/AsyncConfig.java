package com.aiengineering.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Bounded pool for repo clones: a few run at once, a few more wait in the
     * queue, and beyond that submissions are rejected (surfaced as 503)
     * rather than piling up unboundedly.
     */
    @Bean("cloneExecutor")
    public Executor cloneExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("repo-clone-");
        executor.initialize();
        return executor;
    }
}
