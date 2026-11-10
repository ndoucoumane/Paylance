package com.paylance.tenant;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.extern.slf4j.Slf4j;

/**
 * Filet de sécurité complémentaire à {@code @TenantId} : Hibernate 6.5 filtre les requêtes, mais
 * pas le chargement direct par clé ({@code EntityManager.find} / {@code findById}). Toute entité
 * chargée ou écrite hors du tenant courant lève une {@link TenantAccessViolationException}.
 */
@Slf4j
public class TenantGuardListener {

    @PostLoad
    @PrePersist
    @PreUpdate
    public void check(Object entity) {
        if (!(entity instanceof TenantOwned owned) || TenantContext.isSystem()) {
            return;
        }
        String entityOrg = owned.getOrganizationId();
        if (entityOrg == null) {
            return; // insertion : renseigné juste après par Hibernate depuis le tenant courant
        }
        String current = TenantContext.currentOrganizationId().orElse(null);
        if (!entityOrg.equals(current)) {
            log.error("[TENANT_VIOLATION] Accès à {} de l'organisation {} depuis le tenant {}",
                    entity.getClass().getSimpleName(), entityOrg, current);
            throw new TenantAccessViolationException("Ressource introuvable");
        }
    }
}
