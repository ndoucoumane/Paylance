package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentProvider;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
/**
 * Requête de création d'un paiement programmé depuis le dashboard admin.
 * {@code amount} est exprimé en centimes XOF et doit être strictement positif.
 * La valeur minimale autorisée est 1 centime.
 */
public record ScheduledPaymentCreateRequest(

        @NotNull(message = "provider est requis")
        PaymentProvider provider,

        @NotNull(message = "amount est requis")
        @Min(value = 1, message = "amount doit être >= 1 centime")
        Long amount,

        @NotBlank(message = "payerPhone est requis")
        @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "payerPhone invalide")
        String payerPhone,

        @NotNull(message = "scheduledAt est requis")
        @Future(message = "scheduledAt doit être dans le futur")
        LocalDateTime scheduledAt,

        @Size(max = 100)
        String externalReference,

        @Size(max = 500)
        String note
) {
}
