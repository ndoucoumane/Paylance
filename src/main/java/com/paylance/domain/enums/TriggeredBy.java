package com.paylance.domain.enums;

/**
 * Origine d'une transition d'état ou d'une action sur un paiement, utile pour l'audit.
 */
public enum TriggeredBy {
    API,
    WEBHOOK,
    RECONCILIATION,
    MANUAL
}
