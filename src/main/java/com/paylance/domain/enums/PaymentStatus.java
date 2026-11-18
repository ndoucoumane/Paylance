package com.paylance.domain.enums;

/**
 * Cycle de vie d'un paiement orchestré par Paylance.
 */
public enum PaymentStatus {
    PENDING("en attente"),
    PROCESSING("en cours"),
    SUCCESS("réussi"),
    FAILED("échoué"),
    EXPIRED("expiré"),
    REFUNDED("remboursé"),
    PARTIALLY_REFUNDED("partiellement remboursé"),
    CANCELLED("annulé");

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    /** Libellé français, pour les messages affichés au marchand. */
    public String label() {
        return label;
    }

    /** Seuls ces statuts autorisent un nouveau remboursement. */
    public boolean isRefundable() {
        return this == SUCCESS || this == PARTIALLY_REFUNDED;
    }
}
