package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationCreateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 60) @Pattern(regexp = "^[a-z0-9-]*$", message = "slug : minuscules, chiffres et tirets") String slug,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "country : code ISO à 2 lettres") String country,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "defaultCurrency : code ISO à 3 lettres") String defaultCurrency
) {
}
