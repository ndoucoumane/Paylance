package com.kebpay.domain.enums;

/**
 * Cycle de vie d'un paiement programmé (créé depuis le dashboard admin, exécuté
 * automatiquement à la date/heure demandée).
 */
public enum ScheduledPaymentStatus {
    SCHEDULED,
    /** Transition atomique avant l'appel provider — évite la double exécution multi-instances. */
    EXECUTING,
    EXECUTED,
    FAILED,
    CANCELLED
}
