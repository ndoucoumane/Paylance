package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.Platform;
import jakarta.validation.constraints.*;

/**
 * Création d'un paiement à l'initiative d'un agent (dashboard admin) — ex: lien de paiement
 * Wave partagé manuellement à un client qui ne passe pas par une plateforme cliente. Contrairement
 * à {@link PaymentInitiateRequest}, pas d'idempotencyKey/callbackUrl à fournir : chaque création
 * est un nouveau paiement, et il n'y a pas de plateforme externe à notifier en callback.
 */
public record AdminPaymentLinkRequest(

        @NotNull(message = "platform est requis")
        Platform platform,

        @NotNull(message = "provider est requis")
        PaymentProvider provider,

        @NotNull(message = "amount est requis")
        @Positive(message = "amount doit être positif (en centimes)")
        Long amount,

        @Size(max = 10)
        String currency,

        @NotBlank(message = "payerPhone est requis")
        @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "payerPhone invalide")
        String payerPhone,

        @Size(max = 100)
        String payerName,

        @Positive
        Integer expiresInMinutes
) {
}
