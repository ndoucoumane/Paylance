package com.paylance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Active l'exécution asynchrone (callbacks vers les plateformes clientes) et le retry
 * déclaratif ({@code @Retryable}) utilisé par {@link com.paylance.service.PlatformCallbackSender}
 * et {@link com.paylance.service.PaymentStatusTransitionService#transitionStatus}.
 */
@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("paylance-async-");
        executor.initialize();
        return executor;
    }
}
