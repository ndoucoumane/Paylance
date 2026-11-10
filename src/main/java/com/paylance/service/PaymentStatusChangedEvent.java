package com.paylance.service;

import com.paylance.domain.enums.PaymentStatus;

/**
 * Publié après commit d'une transition de statut terminale (SUCCESS/FAILED),
 * déclenche la notification asynchrone de la plateforme cliente ({@link CallbackService}).
 */
public record PaymentStatusChangedEvent(String paymentId, PaymentStatus status) {
}
