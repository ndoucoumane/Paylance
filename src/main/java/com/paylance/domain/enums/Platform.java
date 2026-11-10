package com.paylance.domain.enums;

/**
 * Plateformes internes clientes de Paylance.
 */
public enum Platform {
    AFRISURGELEE,
    KSSID,
    FLEET360,
    /** Paiement reçu directement chez le provider hors initiation Paylance (ex: lien de
     * paiement Wave partagé manuellement à un client) — à rattacher a posteriori. */
    MANUAL
}
