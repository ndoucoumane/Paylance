package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.PaymentStatus;

import java.time.Instant;

public record PaymentInitiateResponse(
        String paymentId,
        /** Numéro lisible à afficher côté dashboard (ex: PAY-AFR-0001) — utiliser {@code paymentId} pour tout appel API. */
        String paymentNumber,
        String correlationId,
        PaymentStatus status,
        PaymentProvider provider,
        Long amount,
        String currency,
        String payerPhone,
        String externalReference,
        /** URL ou code renvoyé par le provider pour finaliser le paiement côté client (si applicable). */
        String providerCheckoutReference,
        /** Instant (UTC explicite en JSON) — pas LocalDateTime, pour ne pas laisser les clients deviner le fuseau. */
        Instant expiresAt,
        Instant createdAt
) {
}
