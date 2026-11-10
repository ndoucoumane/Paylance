package com.paylance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RefundRequest(

        @Positive(message = "amount doit être positif (en centimes) — omettre pour un remboursement total")
        Long amount,

        @NotBlank(message = "reason est requis")
        @Size(max = 500)
        String reason
) {
}
