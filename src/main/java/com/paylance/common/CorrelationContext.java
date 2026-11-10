package com.paylance.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Accès pratique à l'identifiant de corrélation courant (MDC). À utiliser explicitement
 * (et pas seulement via MDC) dans les traitements asynchrones/planifiés où le MDC de la
 * requête HTTP d'origine n'est pas propagé automatiquement au thread d'exécution.
 */
public final class CorrelationContext {

    private CorrelationContext() {
    }

    public static String current() {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        return id != null ? id : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static void set(String correlationId) {
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
    }

    public static void clear() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
}
