package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentStatus;

/**
 * Payload du callback signé envoyé par Paylance à l'application cliente à chaque statut final
 * d'un paiement (en-tête {@code Paylance-Signature}).
 */
public record CallbackPayload(
        String paymentId,
        String correlationId,
        PaymentStatus status,
        String externalReference,
        Long amount,
        String currency,
        String failureReason
) {
}
