package com.paylance.security.apikey;

import com.paylance.domain.enums.EnvironmentType;

/** Appelant serveur à serveur authentifié par une clé API d'application. */
public record ApiKeyPrincipal(String apiKeyId, String organizationId, String applicationId, EnvironmentType environment) {
}
