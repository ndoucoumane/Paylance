package com.paylance.web.dto;

import java.util.List;

public record PaymentStatsResponse(
        long totalCount,
        long totalAmount,
        List<AggregateEntry> byStatus,
        List<AggregateEntry> byProvider,
        List<AggregateEntry> byPlatform
) {
    public record AggregateEntry(String key, long count, long totalAmount) {
    }
}
