package com.paylance.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claims du jeton Keycloak → autorités Spring Security.
 *
 * <ul>
 *   <li><b>Rôles realm</b> ({@code realm_access.roles}) : seuls ceux de {@link #ALLOWED_REALM_ROLES}
 *   sont repris ({@code paylance_super_admin} → {@code ROLE_PAYLANCE_SUPER_ADMIN}). Liste blanche
 *   volontaire : sans elle, un rôle realm nommé {@code org_owner} produirait
 *   {@code ROLE_ORG_OWNER} et donnerait les droits OWNER dans toute organisation dont
 *   l'utilisateur est membre. Les rôles d'organisation viennent uniquement de
 *   organization_members (voir {@link OrganizationContextFilter}).</li>
 *   <li><b>E-mail vérifié</b> : {@code email_verified = true} → {@link OrganizationAccess#EMAIL_VERIFIED}.
 *   Claim absent ou autre valeur = non vérifié (refus par défaut).</li>
 * </ul>
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final Set<String> ALLOWED_REALM_ROLES = Set.of(OrganizationAccess.SUPER_ADMIN);

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            roles.stream()
                    .map(role -> role.toString().toUpperCase())
                    .filter(ALLOWED_REALM_ROLES::contains)
                    .distinct()
                    .forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            authorities.add(new SimpleGrantedAuthority(OrganizationAccess.EMAIL_VERIFIED));
        }
        return authorities;
    }
}
