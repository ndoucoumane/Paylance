package com.kebpay.provider;

import com.kebpay.domain.enums.RefundStatus;

public record ProviderRefundResult(
        String providerRefundId,
        RefundStatus status,
        String rawResponse,
        String failureReason
) {
}
