package com.paylance.provider;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentProvider;

/**
 * Contrat commun implémenté par chaque intégration prestataire (Wave, Orange Money...).
 * Chaque appel reçoit la {@link ProviderConnection} du compte marchand concerné : un client
 * prestataire ne détient aucun identifiant global, il agit toujours pour le compte d'un client
 * Paylance (les fonds vont directement sur le compte de ce client).
 */
public interface PaymentProviderClient {

    PaymentProvider getProvider();

    /** Initie un paiement chez le prestataire et retourne sa réponse normalisée. */
    ProviderInitiateResult initiate(ProviderConnection connection, Payment payment);

    /** Interroge le statut réel d'une transaction chez le prestataire (réconciliation). */
    ProviderStatusResult checkStatus(ProviderConnection connection, Payment payment);

    /** Déclenche un remboursement (total ou partiel) chez le prestataire. */
    ProviderRefundResult refund(ProviderConnection connection, Payment payment, Long amount, String reason);

    /** Versement direct (payout) vers le numéro du bénéficiaire. */
    ProviderPayoutResult payout(ProviderConnection connection, Payment payment);

    /** Vérifie l'authenticité d'un webhook reçu pour ce compte, sur le payload brut. */
    boolean verifyWebhookSignature(ProviderConnection connection, String rawPayload, String signatureHeader);

    /** Extrait les informations normalisées d'un webhook brut propre au format de ce prestataire. */
    WebhookParseResult parseWebhookEvent(String rawPayload);

    /** Appel en lecture seule, sans effet de bord, vérifiant que les identifiants sont acceptés. */
    ConnectionTestResult testConnection(ProviderConnection connection);
}
