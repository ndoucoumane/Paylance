package com.paylance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.repository.OrganizationRepository;
import com.paylance.security.apikey.ApiKeyService;
import com.paylance.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static com.paylance.security.OrganizationAccess.MANAGE;
import static com.paylance.security.OrganizationAccess.READ;
import static com.paylance.security.OrganizationAccess.SUPER_ADMIN;
import static com.paylance.security.OrganizationAccess.WRITE;

/**
 * Deux modes d'authentification, dans des chaînes séparées (une clé {@code Bearer pl_...} n'est
 * jamais interprétée comme un JWT) :
 * <ol>
 *   <li>serveur à serveur ({@code /api/v1/payments/**}) : clé API d'application ;</li>
 *   <li>webhooks prestataires : publics, authentifiés par signature dans WebhookService ;</li>
 *   <li>dashboard / onboarding / admin : JWT Keycloak. Non branché tant que
 *   {@code PAYLANCE_KEYCLOAK_ISSUER_URI} est vide (ces routes répondent alors 401).</li>
 * </ol>
 * Pas de clé admin statique : l'administration de la plateforme exige le rôle realm Keycloak
 * {@code PAYLANCE_SUPER_ADMIN}. L'actuator (hors health/info) n'est servi que sur le port de
 * management, lié à 127.0.0.1.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyService apiKeyService;
    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JwtDecoder> jwtDecoderOverride;

    @Value("${paylance.security.keycloak-issuer-uri:}")
    private String keycloakIssuerUri;

    @Value("${paylance.security.dashboard-origins:http://localhost:5183}")
    private String dashboardOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(dashboardOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key",
                OrganizationAccess.HEADER, "X-Correlation-Id"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiKeyChain(HttpSecurity http) throws Exception {
        JsonAuthenticationEntryPoint errors = new JsonAuthenticationEntryPoint(objectMapper);
        http.securityMatcher("/api/v1/payments", "/api/v1/payments/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().hasRole(ApiKeyAuthenticationToken.ROLE))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain userChain(HttpSecurity http) throws Exception {
        JsonAuthenticationEntryPoint errors = new JsonAuthenticationEntryPoint(objectMapper);
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                        // Plateforme
                        .requestMatchers("/api/v1/admin/**").hasRole(SUPER_ADMIN)

                        // Onboarding : tout utilisateur authentifié peut créer / lister ses organisations
                        .requestMatchers("/api/v1/organizations", "/api/v1/organizations/**").authenticated()

                        // Dashboard : lecture pour tout membre
                        .requestMatchers(HttpMethod.GET, "/api/v1/dashboard/**").hasAnyRole(READ)
                        // Clés API, comptes prestataires, membres, organisation, remboursements, versements : OWNER / ADMIN
                        .requestMatchers("/api/v1/dashboard/applications/*/api-keys/**",
                                "/api/v1/dashboard/applications/*/api-keys",
                                "/api/v1/dashboard/applications/*/signing-secret/**",
                                "/api/v1/dashboard/api-keys/**",
                                "/api/v1/dashboard/provider-accounts/**", "/api/v1/dashboard/provider-accounts",
                                "/api/v1/dashboard/members/**", "/api/v1/dashboard/members",
                                "/api/v1/dashboard/organization",
                                "/api/v1/dashboard/payments/*/refunds", "/api/v1/dashboard/refunds/**",
                                "/api/v1/dashboard/scheduled-payments/**", "/api/v1/dashboard/scheduled-payments")
                        .hasAnyRole(MANAGE)
                        // Applications et liens de paiement : OWNER / ADMIN / DEVELOPER
                        .requestMatchers("/api/v1/dashboard/**").hasAnyRole(WRITE)
                        .anyRequest().denyAll())
                .addFilterAfter(new OrganizationContextFilter(organizationService, organizationRepository, objectMapper),
                        BearerTokenAuthenticationFilter.class);

        JwtDecoder decoder = jwtDecoder();
        if (decoder != null) {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint(errors)
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        }
        return http.build();
    }

    /** Un JwtDecoder déclaré ailleurs (tests) est prioritaire ; sinon Keycloak s'il est configuré. */
    private JwtDecoder jwtDecoder() {
        JwtDecoder override = jwtDecoderOverride.getIfAvailable();
        if (override != null) {
            return override;
        }
        if (keycloakIssuerUri != null && !keycloakIssuerUri.isBlank()) {
            // Résolution paresseuse de l'issuer : pas d'appel réseau au démarrage.
            return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(keycloakIssuerUri));
        }
        return null;
    }
}
