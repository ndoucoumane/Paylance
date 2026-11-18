package com.paylance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.security.apikey.ApiKeyPrincipal;
import com.paylance.security.apikey.ApiKeyService;
import com.paylance.tenant.TenantContext;
import com.paylance.common.CorrelationContext;
import com.paylance.web.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Authentification serveur à serveur : {@code Authorization: Bearer pl_live_...}. La clé résout
 * l'application et l'organisation ; l'organisation devient le tenant de toute la requête.
 */
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            writeUnauthorized(request, response, "En-tête Authorization: Bearer <clé API> requis");
            return;
        }
        Optional<ApiKeyPrincipal> principal = apiKeyService.authenticate(header.substring(BEARER.length()).trim());
        if (principal.isEmpty()) {
            writeUnauthorized(request, response, "Clé API invalide");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(principal.get()));
        TenantContext.set(principal.get().organizationId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name()); // messages accentués
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(Instant.now(), HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized", message, request.getRequestURI(), CorrelationContext.current(), null));
    }
}
