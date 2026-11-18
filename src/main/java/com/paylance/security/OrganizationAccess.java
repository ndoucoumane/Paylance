package com.paylance.security;

import com.paylance.domain.enums.MemberRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Optional;

/** Rôles et autorités liés à l'organisation courante du dashboard. */
public final class OrganizationAccess {

    public static final String SUPER_ADMIN = "PAYLANCE_SUPER_ADMIN";
    public static final String HEADER = "X-Organization-Id";

    /** Rôles (sans préfixe ROLE_) autorisés en lecture, écriture courante, et administration. */
    public static final String[] READ = {"ORG_OWNER", "ORG_ADMIN", "ORG_DEVELOPER", "ORG_VIEWER"};
    public static final String[] WRITE = {"ORG_OWNER", "ORG_ADMIN", "ORG_DEVELOPER"};
    public static final String[] MANAGE = {"ORG_OWNER", "ORG_ADMIN"};

    private OrganizationAccess() {
    }

    /** Rôle de l'utilisateur courant dans l'organisation sélectionnée. */
    public static Optional<MemberRole> currentRole() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return Arrays.stream(MemberRole.values())
                .filter(role -> authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch(role.authority()::equals))
                .findFirst();
    }

    public static MemberRole requireCurrentRole() {
        return currentRole().orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Aucun rôle dans cette organisation"));
    }
}
