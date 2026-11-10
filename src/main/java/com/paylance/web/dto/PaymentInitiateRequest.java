package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentProvider;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * Requête d'initiation de paiement envoyée par une plateforme cliente.
 */
public record PaymentInitiateRequest(

        @NotBlank(message = "idempotencyKey est requis")
        @Size(max = 100)
        String idempotencyKey,

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

        @Size(max = 100)
        String externalReference,

        @NotBlank(message = "callbackUrl est requis")
        @Size(max = 500)
        String callbackUrl,

        @Positive
        Integer expiresInMinutes,

        Map<String, Object> metadata,

        /** Redirection du payeur après succès (sinon : URL par défaut de l'application). */
        @Size(max = 500)
        String successUrl,

        /** Redirection du payeur après échec/annulation (sinon : URL par défaut de l'application). */
        @Size(max = 500)
        String errorUrl
) {
}
