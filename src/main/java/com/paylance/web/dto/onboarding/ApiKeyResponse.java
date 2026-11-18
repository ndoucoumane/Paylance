package com.paylance.web.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paylance.domain.entity.ApiKey;
import com.paylance.domain.enums.EnvironmentType;

import java.time.LocalDateTime;

/** {@code key} (clé en clair) n'est renvoyé qu'une seule fois, à la création. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeyResponse(String id, String applicationId, String label, String prefix, String lastFour,
                             EnvironmentType environment, Boolean active, LocalDateTime expiresAt,
                             LocalDateTime lastUsedAt, LocalDateTime revokedAt, LocalDateTime createdAt, String key) {

    public static ApiKeyResponse of(ApiKey k, String plaintext) {
        return new ApiKeyResponse(k.getId(), k.getApplicationId(), k.getLabel(), k.getPrefix(), k.getLastFour(),
                k.getEnvironment(), k.getActive(), k.getExpiresAt(), k.getLastUsedAt(), k.getRevokedAt(),
                k.getCreatedAt(), plaintext);
    }
}
