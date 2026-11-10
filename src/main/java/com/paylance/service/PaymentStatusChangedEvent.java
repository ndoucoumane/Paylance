package com.paylance.service;

import com.paylance.domain.enums.PaymentStatus;

/**
 * Publié après commit d'une transition de statut finale (SUCCESS/FAILED) ; déclenche le callback
 * asynchrone vers l'application cliente ({@link CallbackService}) dans le tenant du paiement.
 */
public record PaymentStatusChangedEvent(String paymentId, String organizationId, PaymentStatus status) {
}
