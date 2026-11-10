package com.kebpay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kebpayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("KebPay API")
                        .description("Middleware de paiement interne — orchestration Wave / Orange Money")
                        .version("v1")
                        .contact(new Contact().name("KebPay").email("payments@kebpay.internal")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key"))
                        .addSecuritySchemes("platform", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Platform")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey").addList("platform"));
    }
}
