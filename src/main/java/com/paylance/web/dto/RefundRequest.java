package com.paylance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** {@code amount} absent = remboursement total du solde restant. Montant en centimes. */
public record RefundRequest(

        @Positive(message = "Le montant du remboursement doit être supérieur à 0.")
        Long amount,

        @NotBlank(message = "Indiquez le motif du remboursement.")
        @Size(max = 500, message = "Le motif ne doit pas dépasser 500 caractères.")
        String reason
) {
}
