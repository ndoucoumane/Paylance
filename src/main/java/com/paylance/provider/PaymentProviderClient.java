package com.paylance.provider;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentProvider;

/**
 * Contrat commun implémenté par chaque intégration provider (Wave, Orange Money...).
 * Permet à {@code PaymentService} d'orchestrer les paiements sans connaître les
 * spécificités de chaque API externe.
 */
public interface PaymentProviderClient {

    PaymentProvider getProvider();

    /**
     * Initie un paiement chez le provider et retourne sa réponse normalisée.
     */
    ProviderInitiateResult initiate(Payment payment);

    /**
     * Interroge le statut réel d'une transaction chez le provider (utilisé par la reconciliation).
     */
    ProviderStatusResult checkStatus(Payment payment);

    /**
     * Déclenche un remboursement (total ou partiel) chez le provider.
     */
    ProviderRefundResult refund(Payment payment, Long amount, String reason);

    /**
     * Déclenche un virement / déboursement direct (Payout) vers le numéro de téléphone du bénéficiaire.
     */
    ProviderPayoutResult payout(Payment payment);

    /**
     * Vérifie la signature HMAC d'un webhook reçu de ce provider sur le payload brut.
     */
    boolean verifyWebhookSignature(String rawPayload, String signatureHeader);

    /**
     * Extrait les informations normalisées (référence transaction, statut...) d'un webhook
     * brut propre au format de ce provider.
     */
    WebhookParseResult parseWebhookEvent(String rawPayload);
}
