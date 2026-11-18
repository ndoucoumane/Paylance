package com.paylance.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active {@code @Scheduled}, utilisé par la réconciliation et les tâches programmées.
 * Désactivable ({@code paylance.scheduling.enabled=false}), notamment dans les tests d'intégration.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paylance.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
