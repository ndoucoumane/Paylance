package com.paylance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.paylance.domain.enums.RefundStatus;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.ALWAYS)
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
