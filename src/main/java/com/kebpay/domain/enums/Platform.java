package com.kebpay.domain.enums;

/**
 * Plateformes internes clientes de KebPay.
 */
public enum Platform {
    AFRISURGELEE,
    KSSID,
    FLEET360,
    /** Paiement reçu directement chez le provider hors initiation KebPay (ex: lien de
     * paiement Wave partagé manuellement à un client) — à rattacher a posteriori. */
    MANUAL
}
