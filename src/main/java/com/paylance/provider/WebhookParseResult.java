package com.paylance.provider;

import com.paylance.domain.enums.PaymentStatus;

/**
 * Résultat normalisé de l'extraction d'un webhook provider, indépendant du format brut
 * propre à chaque provider (Wave / Orange Money ont des schémas de payload différents).
 */
public record WebhookParseResult(
        String providerEventId,
        String providerTransactionId,
        PaymentStatus status,
        String failureReason,
        /** Renseigné uniquement quand l'événement correspond à un paiement reçu hors de
         * toute initiation Paylance (ex: {@code merchant.payment_received} Wave — lien de
         * paiement partagé manuellement) : permet de créer la transaction a posteriori
         * au lieu de l'ignorer faute de {@code providerTransactionId} déjà connu. */
        UnattributedPayment unattributed
) {
    public record UnattributedPayment(Long amount, String currency, String payerPhone) {
    }
}
