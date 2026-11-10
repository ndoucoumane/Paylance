package com.paylance.domain.enums;

/**
 * Environnement d'une application, d'une clé API ou d'un compte prestataire.
 * Une clé {@code pl_test_} ne peut utiliser qu'un compte prestataire TEST, et inversement.
 */
public enum EnvironmentType {
    TEST,
    LIVE;

    public String apiKeyPrefix() {
        return this == LIVE ? "pl_live_" : "pl_test_";
    }
}
