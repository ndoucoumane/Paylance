package com.kebpay.domain.enums;

/**
 * Cycle de vie d'un paiement orchestré par KebPay.
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
