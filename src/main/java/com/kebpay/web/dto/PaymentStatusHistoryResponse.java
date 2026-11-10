package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.TriggeredBy;

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
