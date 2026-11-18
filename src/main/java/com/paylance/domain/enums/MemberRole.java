package com.paylance.domain.enums;

/**
 * Rôle d'un utilisateur dans une organisation.
 * VIEWER : lecture seule. DEVELOPER : lecture + applications et paiements.
 * ADMIN / OWNER : tout, y compris clés API, comptes prestataires et remboursements.
 */
public enum MemberRole {
    OWNER,
    ADMIN,
    DEVELOPER,
    VIEWER;

    /** Autorité Spring Security correspondante (ex : ROLE_ORG_ADMIN). */
    public String authority() {
        return "ROLE_ORG_" + name();
    }
}
