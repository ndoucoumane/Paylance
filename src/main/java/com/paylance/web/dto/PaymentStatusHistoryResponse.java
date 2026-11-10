package com.paylance.web.dto;

import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.TriggeredBy;

import java.time.LocalDateTime;

public record PaymentStatusHistoryResponse(
        String id,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String reason,
        TriggeredBy triggeredBy,
        LocalDateTime createdAt
) {
}
