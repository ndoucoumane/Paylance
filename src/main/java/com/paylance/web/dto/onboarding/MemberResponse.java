package com.paylance.web.dto.onboarding;

import com.paylance.domain.entity.OrganizationMember;
import com.paylance.domain.enums.MemberRole;

import java.time.LocalDateTime;

public record MemberResponse(String id, String keycloakUserId, String email, MemberRole role, LocalDateTime createdAt) {

    public static MemberResponse of(OrganizationMember m) {
        return new MemberResponse(m.getId(), m.getKeycloakUserId(), m.getEmail(), m.getRole(), m.getCreatedAt());
    }
}
