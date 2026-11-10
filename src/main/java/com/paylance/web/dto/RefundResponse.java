package com.paylance.web.dto;

import com.paylance.domain.enums.RefundStatus;

import java.time.LocalDateTime;

public record RefundResponse(
        String refundId,
        String paymentId,
        String paymentNumber,
        String payerPhone,
        Long amount,
        RefundStatus status,
        String reason,
        String failureReason,
        String providerRefundId,
        String requestedBy,
        LocalDateTime createdAt
) {
}
