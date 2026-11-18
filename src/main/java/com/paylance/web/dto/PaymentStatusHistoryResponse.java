package com.paylance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.TriggeredBy;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentStatusHistoryResponse(
        String id,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String reason,
        TriggeredBy triggeredBy,
        LocalDateTime createdAt
) {
}
