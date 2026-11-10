package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentProvider;
import jakarta.validation.constraints.*;

/**
 * Lien de paiement créé depuis le dashboard (ex : paiement demandé par un agent à un client).
 * Rattaché à une application si {@code applicationId} est fourni, sinon paiement « manuel ».
 */
public record PaymentLinkRequest(

        @Size(max = 36)
        String applicationId,

        @NotNull(message = "provider est requis")
        PaymentProvider provider,

        @Size(max = 36)
        String providerAccountId,

        @NotNull(message = "amount est requis")
        @Positive(message = "amount doit être positif (en centimes)")
        Long amount,

        @Size(max = 10)
        String currency,

        @NotBlank(message = "payerPhone est requis")
        @Pattern(regexp = "^\\+?[0-9 .\\-]{8,20}$", message = "payerPhone invalide")
        String payerPhone,

        @Size(max = 100)
        String payerName,

        @Size(max = 500)
        String successUrl,

        @Size(max = 500)
        String errorUrl,

        @Positive
        Integer expiresInMinutes
) {
}
