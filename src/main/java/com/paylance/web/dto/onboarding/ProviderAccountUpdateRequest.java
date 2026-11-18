package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.Size;

import java.util.Map;

/** Champs absents = inchangés. Les identifiants se remplacent en bloc (jamais relus en clair). */
public record ProviderAccountUpdateRequest(
        @Size(max = 150) String label,
        Map<String, String> credentials,
        @Size(max = 500) String webhookSecret
) {
}
