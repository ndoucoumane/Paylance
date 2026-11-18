package com.paylance.web.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ProviderAccountStatus;

import java.time.Instant;
import java.util.Map;

/** Identifiants toujours masqués ; {@code webhookUrl} est l'URL à déclarer chez le prestataire. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderAccountResponse(String id, PaymentProvider provider, String label, EnvironmentType environment,
                                      ProviderAccountStatus status, Map<String, String> credentials,
                                      boolean webhookSecretConfigured, String webhookUrl,
                                      Instant lastVerifiedAt, String lastVerificationMessage,
                                      Instant createdAt, Instant updatedAt) {
}
