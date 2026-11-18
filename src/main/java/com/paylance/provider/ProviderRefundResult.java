package com.paylance.provider;

import com.paylance.domain.enums.RefundStatus;

public record ProviderRefundResult(
        String providerRefundId,
        RefundStatus status,
        String rawResponse,
        String failureReason
) {
}
