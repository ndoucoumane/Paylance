package com.paylance.web.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.OrganizationStatus;

import java.time.Instant;
import java.time.ZoneOffset;

/** {@code role} : rôle de l'utilisateur courant dans cette organisation, lu en base. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrganizationResponse(String id, String name, String slug, String country, String defaultCurrency,
                                   OrganizationStatus status, MemberRole role, Instant createdAt) {

    public static OrganizationResponse of(Organization o, MemberRole role) {
        // La JVM tourne en UTC (PaylanceApplication) : les LocalDateTime stockés sont de l'UTC.
        return new OrganizationResponse(o.getId(), o.getName(), o.getSlug(), o.getCountry(), o.getDefaultCurrency(),
                o.getStatus(), role, o.getCreatedAt() == null ? null : o.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
