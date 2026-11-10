package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.Platform;

import java.time.Instant;

public record PaymentStatusResponse(
        String paymentId,
        /** Numéro lisible à afficher côté dashboard (ex: PAY-AFR-0001) — utiliser {@code paymentId} pour tout appel API. */
        String paymentNumber,
        String correlationId,
        Platform platform,
        PaymentProvider provider,
        PaymentStatus status,
        Long amount,
        String currency,
        String payerPhone,
        /** Numéro ayant effectivement réglé le paiement côté provider, quand connu (Orange Money
         * uniquement) — peut différer de {@code payerPhone}, déclaré à la création de la commande. */
        String actualPayerPhone,
        String externalReference,
        String failureReason,
        /** Instant (UTC explicite en JSON) — pas LocalDateTime, pour ne pas laisser les clients deviner le fuseau. */
        Instant paidAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
