package com.paylance.web.dto.onboarding;

import com.paylance.domain.enums.EnvironmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Le nom est libre : c'est le client qui nomme ses applications. */
public record ApplicationCreateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 60) @Pattern(regexp = "^[a-z0-9-]*$", message = "slug : minuscules, chiffres et tirets") String slug,
        @NotNull EnvironmentType environment,
        @Size(max = 500) String callbackUrl,
        @Size(max = 500) String successUrl,
        @Size(max = 500) String errorUrl
) {
}
