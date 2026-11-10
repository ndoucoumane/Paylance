package com.kebpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kebpay.repository.PlatformApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final PlatformApiKeyRepository platformApiKeyRepository;
    private final ObjectMapper objectMapper;

    @Value("${kebpay.security.admin-key-hash:}")
    private String adminKeyHash;

    // Origines du/des dashboard(s) front KebPay autorisées en CORS (une par environnement,
    // séparées par des virgules — voir KEBPAY_DASHBOARD_ORIGINS).
    @Value("${kebpay.security.dashboard-origins:http://localhost:5183}")
    private String dashboardOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(dashboardOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "Authorization", "X-Admin-Key", "X-Platform", "X-API-Key"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, PasswordEncoder passwordEncoder) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(platformApiKeyRepository, passwordEncoder, objectMapper);
        AdminApiKeyFilter adminApiKeyFilter = new AdminApiKeyFilter(adminKeyHash, passwordEncoder, objectMapper);

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // preflight CORS
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/swagger-ui.html", // point d'entrée (redirige vers /swagger-ui/index.html) — distinct de /swagger-ui/**
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api/v1/webhooks/**" // authentifié par signature HMAC, pas par API key
                        ).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // /api/v1/admin/** : login Keycloak (dashboard), pas la clé statique —
                        // voir oauth2ResourceServer ci-dessous, pas addFilterBefore(adminApiKeyFilter).
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("PLATFORM")
                        .anyRequest().denyAll()
                )
                // Rôle realm Keycloak "admin" -> ROLE_ADMIN (KeycloakRoleConverter), donc
                // hasRole("ADMIN") ci-dessus fonctionne pour un token Keycloak comme pour
                // AdminApiKeyFilter (actuator uniquement désormais, voir sa Javadoc).
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterBefore(adminApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
