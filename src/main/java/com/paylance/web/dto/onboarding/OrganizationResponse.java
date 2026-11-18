package com.paylance.web.dto.onboarding;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.OrganizationStatus;

import java.time.LocalDateTime;

public record OrganizationResponse(String id, String name, String slug, String country, String defaultCurrency,
                                   OrganizationStatus status, MemberRole role, LocalDateTime createdAt) {

    public static OrganizationResponse of(Organization o, MemberRole role) {
        return new OrganizationResponse(o.getId(), o.getName(), o.getSlug(), o.getCountry(), o.getDefaultCurrency(),
                o.getStatus(), role, o.getCreatedAt());
    }
}
