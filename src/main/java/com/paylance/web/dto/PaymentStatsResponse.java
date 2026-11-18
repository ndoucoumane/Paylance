package com.paylance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentStatsResponse(
        long totalCount,
        long totalAmount,
        List<AggregateEntry> byStatus,
        List<AggregateEntry> byProvider,
        List<AggregateEntry> byApplication
) {
    public record AggregateEntry(String key, long count, long totalAmount) {
    }
}
