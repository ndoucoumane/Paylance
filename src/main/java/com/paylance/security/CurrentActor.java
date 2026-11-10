package com.paylance.security;

import com.paylance.security.apikey.ApiKeyPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Identité de l'appelant courant, pour l'audit (requestedBy, actor...). */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** {@code sub} Keycloak pour un utilisateur du dashboard, {@code null} sinon. */
    public static String userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getSubject();
        }
        return null;
    }

    public static String describe() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String email = jwt.getToken().getClaimAsString("email");
            return "USER:" + (email != null ? email : jwt.getToken().getSubject());
        }
        if (authentication != null && authentication.getPrincipal() instanceof ApiKeyPrincipal key) {
            return "API:" + key.applicationId();
        }
        return "SYSTEM";
    }
}
