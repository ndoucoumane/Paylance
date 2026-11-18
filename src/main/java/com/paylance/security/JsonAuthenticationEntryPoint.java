package com.paylance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.common.CorrelationContext;
import com.paylance.web.dto.ErrorResponse;
import com.paylance.web.exception.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;

/** Réponses 401 / 403 au même format JSON que le reste de l'API. */
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorMessages.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, emailNotVerified() ? ErrorMessages.EMAIL_NOT_VERIFIED : ErrorMessages.FORBIDDEN);
    }

    /** Utilisateur connecté dont le jeton ne porte pas {@code email_verified = true}. */
    private static boolean emailNotVerified() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().noneMatch(a -> OrganizationAccess.EMAIL_VERIFIED.equals(a.getAuthority()));
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name()); // messages accentués
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), CorrelationContext.current(), null));
    }
}
