package com.paylance.service;

import com.paylance.common.PhoneNumbers;
import com.paylance.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Clients finaux (payeurs) d'une organisation. Upsert atomique par (organisation, téléphone) :
 * deux paiements simultanés du même payeur ne créent jamais deux fiches.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final String UPSERT_SQL = """
            INSERT INTO customers (id, organization_id, phone, name, email, external_id, created_at, updated_at)
            VALUES (:id, :organizationId, :phone, :name, :email, :externalId, now(), now())
            ON CONFLICT (organization_id, phone) DO UPDATE SET
                name        = COALESCE(EXCLUDED.name, customers.name),
                email       = COALESCE(EXCLUDED.email, customers.email),
                external_id = COALESCE(EXCLUDED.external_id, customers.external_id),
                updated_at  = now()
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** @return l'identifiant du client (créé ou existant) dans l'organisation courante. */
    public String upsert(String phone, String name, String email, String externalId) {
        String organizationId = TenantContext.requireOrganizationId();
        String normalized = PhoneNumbers.normalize(phone);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("Téléphone du payeur requis");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("organizationId", organizationId)
                .addValue("phone", normalized)
                .addValue("name", blankToNull(name))
                .addValue("email", blankToNull(email))
                .addValue("externalId", blankToNull(externalId));
        return jdbc.queryForObject(UPSERT_SQL, params, String.class);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
