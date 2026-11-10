package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentProvider;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * Initiation d'un paiement par une application cliente (clé API). L'idempotence passe par
 * l'en-tête {@code Idempotency-Key}.
 */
public record PaymentInitiateRequest(

        @NotNull(message = "provider est requis")
        PaymentProvider provider,

        /** Compte marchand à utiliser ; facultatif s'il n'existe qu'un compte actif pour ce prestataire. */
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

        @Email
        @Size(max = 255)
        String payerEmail,

        /** Identifiant du payeur dans le système du client. */
        @Size(max = 100)
        String customerExternalId,

        @Size(max = 100)
        String externalReference,

        /** Surcharge de l'URL de callback de l'application, pour ce paiement uniquement. */
        @Size(max = 500)
        String callbackUrl,

        /** Redirection du payeur après succès (sinon : URL par défaut de l'application). */
        @Size(max = 500)
        String successUrl,

        /** Redirection du payeur après échec/annulation (sinon : URL par défaut de l'application). */
        @Size(max = 500)
        String errorUrl,

        @Positive
        Integer expiresInMinutes,

        Map<String, Object> metadata
) {
}
