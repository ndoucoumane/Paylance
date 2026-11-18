package com.paylance.tenant;

/** Entité appartenant à une organisation (colonne {@code organization_id} marquée {@code @TenantId}). */
public interface TenantOwned {
    String getOrganizationId();
}
