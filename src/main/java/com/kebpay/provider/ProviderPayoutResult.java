package com.kebpay.provider;

import com.kebpay.domain.enums.PaymentStatus;

/**
 * Résultat normalisé d'un virement / déboursement direct (Payout) chez un provider.
 */
public record ProviderPayoutResult(
        String providerTransactionId,
        PaymentStatus status,
        String rawResponse,
        String failureReason
) {
}
