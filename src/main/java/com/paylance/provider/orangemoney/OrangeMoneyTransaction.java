package com.paylance.provider.orangemoney;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Élément renvoyé par {@code GET /api/eWallet/v1/transactions} (recherche par {@code reference}),
 * utilisé pour la réconciliation en l'absence d'un endpoint de statut direct par notre propre ID.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OrangeMoneyTransaction(
        String reference,
        String transactionId,
        String status,
        String statusReason,
        String subscriberMsisdn
) {
}
