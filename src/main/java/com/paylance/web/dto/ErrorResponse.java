package com.paylance.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Corps de toute réponse d'erreur. {@code message} est affiché tel quel par le front pour les
 * 4xx : en français, compréhensible par un commerçant, sans détail interne. {@code timestamp}
 * est un instant UTC (sérialisé avec {@code Z}).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<String> details
) {
}
