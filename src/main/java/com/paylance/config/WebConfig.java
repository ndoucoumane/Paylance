package com.paylance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // Ajouté après les formats par défaut de Spring Boot : prioritaire pour String → LocalDateTime.
        registry.addConverter(new UtcDateTimeParamConverter());
    }
}
