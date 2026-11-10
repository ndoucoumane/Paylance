package com.paylance.security;

import com.paylance.domain.entity.PlatformApiKey;
import com.paylance.domain.enums.Platform;
import com.paylance.repository.PlatformApiKeyRepository;
import com.paylance.web.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Authentifie les plateformes clientes via les headers {@code X-Platform} / {@code X-API-Key}.
 * La clé fournie en clair est comparée au hash BCrypt stocké en base ({@link PlatformApiKey#getApiKeyHash()}).
 */
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String PLATFORM_HEADER = "X-Platform";
    public static final String API_KEY_HEADER = "X-API-Key";

    private final PlatformApiKeyRepository platformApiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Le preflight CORS (OPTIONS) n'envoie jamais les headers custom : il ne doit pas être
        // soumis à cette authentification, sinon le navigateur bloque la vraie requête qui suit.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/webhooks") || path.startsWith("/api/v1/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String platformHeader = request.getHeader(PLATFORM_HEADER);
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (platformHeader == null || apiKey == null) {
            writeUnauthorized(request, response, "Headers X-Platform et X-API-Key requis");
            return;
        }

        Platform platform;
        try {
            platform = Platform.valueOf(platformHeader.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            writeUnauthorized(request, response, "Plateforme inconnue : " + platformHeader);
            return;
        }

        Optional<PlatformApiKey> keyOpt = platformApiKeyRepository.findByPlatformAndIsActiveTrue(platform);
        if (keyOpt.isEmpty()) {
            writeUnauthorized(request, response, "Clé API invalide");
            return;
        }

        PlatformApiKey key = keyOpt.get();
        boolean expired = key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now());
        if (expired || !passwordEncoder.matches(apiKey, key.getApiKeyHash())) {
            log.warn("Tentative d'authentification échouée pour la plateforme {}", platform);
            writeUnauthorized(request, response, "Clé API invalide");
            return;
        }

        key.setLastUsedAt(LocalDateTime.now());
        platformApiKeyRepository.save(key);

        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(new PlatformPrincipal(platform)));
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                message, request.getRequestURI(), null, null);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
