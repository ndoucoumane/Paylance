package com.paylance.web.dto.onboarding;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ProviderAccountStatus;

import java.time.LocalDateTime;
import java.util.Map;

/** Identifiants toujours masqués ; {@code webhookUrl} est l'URL à déclarer chez le prestataire. */
public record ProviderAccountResponse(String id, PaymentProvider provider, String label, EnvironmentType environment,
                                      ProviderAccountStatus status, Map<String, String> credentials,
                                      boolean webhookSecretConfigured, String webhookUrl,
                                      LocalDateTime lastVerifiedAt, String lastVerificationMessage,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
}
