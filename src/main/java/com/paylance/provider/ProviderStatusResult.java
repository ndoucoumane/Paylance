package com.paylance.provider;

import com.paylance.domain.enums.PaymentStatus;

public record ProviderStatusResult(
        PaymentStatus status,
        String rawResponse,
        String failureReason,
        /** Numéro ayant effectivement réglé le paiement, quand le provider le communique
         * (Orange Money via {@code subscriberMsisdn}) — {@code null} pour les providers qui
         * ne restituent pas l'identité du payeur (Wave). */
        String actualPayerPhone
) {
}
