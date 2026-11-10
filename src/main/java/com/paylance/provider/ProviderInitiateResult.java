package com.paylance.provider;

import com.paylance.domain.enums.PaymentStatus;

/**
 * Résultat normalisé de l'initiation d'un paiement chez un provider.
 */
public record ProviderInitiateResult(
        String providerTransactionId,
        /** URL/code de checkout à retourner éventuellement à l'application cliente. */
        String checkoutReference,
        PaymentStatus status,
        String rawResponse
) {
}
