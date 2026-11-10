package com.kebpay.provider;

import com.kebpay.domain.enums.PaymentStatus;

/**
 * Résultat normalisé de l'initiation d'un paiement chez un provider.
 */
public record ProviderInitiateResult(
        String providerTransactionId,
        /** URL/code de checkout à retourner éventuellement à la plateforme cliente. */
        String checkoutReference,
        PaymentStatus status,
        String rawResponse
) {
}
