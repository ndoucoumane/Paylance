package com.paylance.web.dto;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;

import java.time.Instant;

public record PaymentStatusResponse(
        String paymentId,
        /** Numéro lisible à afficher (ex : PL-261110-000001-7) — utiliser {@code paymentId} pour tout appel API. */
        String paymentNumber,
        String correlationId,
        String applicationId,
        String providerAccountId,
        String customerId,
        EnvironmentType environment,
        PaymentProvider provider,
        PaymentStatus status,
        Long amount,
        String currency,
        String payerPhone,
        /** Numéro ayant effectivement réglé le paiement, quand le prestataire le communique (Orange Money). */
        String actualPayerPhone,
        String externalReference,
        String failureReason,
        /** Instants UTC explicites en JSON. */
        Instant paidAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
