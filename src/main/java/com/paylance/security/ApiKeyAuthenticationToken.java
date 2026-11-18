package com.paylance.security;

import com.paylance.security.apikey.ApiKeyPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    public static final String ROLE = "API_CLIENT";

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthenticationToken(ApiKeyPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }
}
