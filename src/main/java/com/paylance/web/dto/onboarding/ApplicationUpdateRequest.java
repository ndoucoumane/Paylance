package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.Size;

/** Champs absents = inchangés ; chaîne vide = URL effacée. */
public record ApplicationUpdateRequest(
        @Size(max = 150) String name,
        @Size(max = 500) String callbackUrl,
        @Size(max = 500) String successUrl,
        @Size(max = 500) String errorUrl,
        Boolean active
) {
}
