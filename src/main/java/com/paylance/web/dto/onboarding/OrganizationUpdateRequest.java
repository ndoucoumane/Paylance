package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationUpdateRequest(
        @Size(max = 150) String name,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "defaultCurrency : code ISO à 3 lettres") String defaultCurrency
) {
}
