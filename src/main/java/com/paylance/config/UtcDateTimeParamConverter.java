package com.paylance.config;

import org.springframework.core.convert.converter.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Paramètres de date des routes ({@code from}, {@code to}...) → {@link LocalDateTime} en UTC,
 * le fuseau dans lequel les entités sont stockées.
 *
 * <ul>
 *   <li>avec fuseau ({@code 2026-10-20T00:00:00Z}, {@code ...+01:00}) : converti en UTC ;</li>
 *   <li>sans fuseau ({@code 2026-10-20T00:00:00}, format actuel du front) : lu comme de l'UTC,
 *   jamais comme l'heure locale du serveur.</li>
 * </ul>
 * Une valeur illisible lève une exception, traduite en 400 par GlobalExceptionHandler.
 */
public class UtcDateTimeParamConverter implements Converter<String, LocalDateTime> {

    @Override
    public LocalDateTime convert(String source) {
        String value = source.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }
}
