package com.paylance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.web.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Authentifie les appels aux endpoints d'exploitation (métriques/actuator) via un header
 * {@code X-Admin-Key} comparé au hash BCrypt configuré ({@code paylance.security.admin-key-hash}).
 * Utilisé par les outils machine-à-machine (Prometheus...) qui ne peuvent pas faire un login
 * interactif Keycloak. Le dashboard admin ({@code /api/v1/admin/**}) est authentifié séparément
 * par JWT Keycloak — voir {@link SecurityConfig}.
 *
 * <p>Enregistré avant le filtre {@code oauth2ResourceServer} dans la chaîne ({@code SecurityConfig}) :
 * pour {@code /actuator/**}, seul {@code X-Admin-Key} est pris en compte — un JWT Keycloak valide
 * présent sur ces routes est ignoré, cette clé reste le seul mécanisme accepté ici (comportement
 * volontaire, pas un oubli).
 */
@RequiredArgsConstructor
public class AdminApiKeyFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final String adminKeyHash;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Le preflight CORS (OPTIONS) n'envoie jamais X-Admin-Key : il ne doit pas être soumis
        // à cette authentification, sinon le navigateur bloque la vraie requête qui suit.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        boolean isProtectedActuator = path.startsWith("/actuator") && !path.startsWith("/actuator/health") && !path.startsWith("/actuator/info");
        return !isProtectedActuator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String adminKey = request.getHeader(ADMIN_KEY_HEADER);
        if (adminKey == null || adminKeyHash == null || adminKeyHash.isBlank() || !passwordEncoder.matches(adminKey, adminKeyHash)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorResponse body = new ErrorResponse(
                    LocalDateTime.now(), HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                    "Clé administrateur invalide", request.getRequestURI(), null, null);
            objectMapper.writeValue(response.getWriter(), body);
            return;
        }

        var authentication = new AbstractAuthenticationToken(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))) {
            @Override
            public Object getCredentials() {
                return null;
            }

            @Override
            public Object getPrincipal() {
                return "admin";
            }
        };
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
