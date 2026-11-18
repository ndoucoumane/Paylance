package com.paylance.service;

import com.paylance.domain.enums.EnvironmentType;

/**
 * Qui initie un paiement : une application (clé API, environnement imposé) ou un membre du
 * dashboard (application facultative, environnement déduit du compte prestataire).
 */
public record PaymentContext(String organizationId, String applicationId, EnvironmentType environment, String actor) {

    public static PaymentContext forApiKey(String organizationId, String applicationId, EnvironmentType environment) {
        return new PaymentContext(organizationId, applicationId, environment, "API:" + applicationId);
    }

    public static PaymentContext forDashboard(String organizationId, String applicationId, String userId) {
        return new PaymentContext(organizationId, applicationId, null, "USER:" + userId);
    }

    public static PaymentContext forSystem(String organizationId, String reason) {
        return new PaymentContext(organizationId, null, null, "SYSTEM:" + reason);
    }
}
