package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentStatus;

/**
 * Payload envoyé par KebPay à la plateforme cliente (Afrisurgelée, Kssid, Fleet360...) pour
 * l'informer qu'une commande peut être traitée (ou non).
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
