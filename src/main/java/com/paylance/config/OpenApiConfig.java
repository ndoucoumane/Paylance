package com.paylance.config;

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
    public OpenAPI paylanceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Paylance API")
                        .description("Orchestration de paiements multi-tenant (Wave, Orange Money) — les fonds vont directement sur les comptes marchands des clients, Paylance ne les détient jamais")
                        .version("v1")
                        .contact(new Contact().name("Paylance")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("Clé API d'application : pl_live_... ou pl_test_..."))
                        .addSecuritySchemes("dashboard", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("JWT Keycloak + en-tête X-Organization-Id")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
