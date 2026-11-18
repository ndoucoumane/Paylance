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

    /**
     * Nom à afficher pour l'utilisateur courant (« Prénom Nom »), lu dans le jeton validé.
     * Repli : claim {@code name}, puis e-mail, puis {@code sub}.
     */
    public static String displayName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return describe();
        }
        var token = jwt.getToken();
        String fullName = (text(token.getClaimAsString("given_name")) + " " + text(token.getClaimAsString("family_name"))).trim();
        if (!fullName.isEmpty()) {
            return truncate(fullName);
        }
        for (String claim : new String[]{"name", "email"}) {
            String value = text(token.getClaimAsString(claim)).trim();
            if (!value.isEmpty()) {
                return truncate(value);
            }
        }
        return token.getSubject();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** Colonne requested_by : 150 caractères. */
    private static String truncate(String value) {
        return value.length() <= 150 ? value : value.substring(0, 150);
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
