package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.ScheduledPaymentStatus;

import java.time.LocalDateTime;

public record ScheduledPaymentResponse(
        String id,
        PaymentProvider provider,
        Platform platform,
        /** Montant en centimes XOF — voir {@link ScheduledPaymentCreateRequest}. */
        Long amount,
        String payerPhone,
        LocalDateTime scheduledAt,
        String externalReference,
        String note,
        ScheduledPaymentStatus status,
        /** Renseigné une fois exécuté : identifiant du paiement réellement créé chez le provider. */
        String paymentId,
        /** Numéro lisible du paiement créé après exécution (ex: PL-260820-000190-5). */
        String paymentNumber,
        String failureReason,
        LocalDateTime executedAt,
        LocalDateTime createdAt
) {
}
