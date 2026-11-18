package com.paylance.web.dto.onboarding;

import com.paylance.domain.enums.MemberRole;
import jakarta.validation.constraints.NotNull;

public record MemberRoleUpdateRequest(@NotNull MemberRole role) {
}
