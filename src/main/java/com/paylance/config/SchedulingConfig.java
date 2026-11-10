package com.paylance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active {@code @Scheduled}, utilisé par {@link com.paylance.service.ReconciliationService}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
