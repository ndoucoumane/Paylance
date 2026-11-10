package com.paylance.tenant;

/**
 * Tentative d'accès à une ligne d'une autre organisation. Exposée comme une ressource
 * introuvable (404) : on ne révèle jamais l'existence d'une donnée d'un autre tenant.
 */
public class TenantAccessViolationException extends RuntimeException {
    public TenantAccessViolationException(String message) {
        super(message);
    }
}
