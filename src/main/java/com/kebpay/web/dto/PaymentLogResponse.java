package com.kebpay.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.kebpay.domain.enums.LogLevel;

import java.time.LocalDateTime;

public record PaymentLogResponse(
        String id,
        LogLevel level,
        String event,
        String message,
        /** JSON imbriqué si {@code payload} stocké est un JSON valide, sinon chaîne brute. */
        JsonNode payload,
        String correlationId,
        LocalDateTime createdAt
) {
}
