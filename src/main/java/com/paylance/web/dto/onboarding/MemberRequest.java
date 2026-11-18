package com.paylance.web.dto.onboarding;

import com.paylance.domain.enums.MemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Ajout d'un membre par son identifiant Keycloak ({@code sub}). */
public record MemberRequest(
        @NotBlank @Size(max = 100) String keycloakUserId,
        @Email @Size(max = 255) String email,
        @NotNull MemberRole role
) {
}
