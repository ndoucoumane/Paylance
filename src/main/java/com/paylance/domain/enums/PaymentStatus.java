package com.paylance.domain.enums;

/**
 * Cycle de vie d'un paiement orchestré par Paylance.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    EXPIRED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    CANCELLED
}
