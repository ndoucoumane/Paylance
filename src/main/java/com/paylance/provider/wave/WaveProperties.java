package com.paylance.provider.wave;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration globale NON secrète de l'intégration Wave. Les clés API et le secret webhook
 * appartiennent à chaque compte marchand ({@code ProviderAccount}), jamais à la configuration.
 */
@ConfigurationProperties(prefix = "paylance.provider.wave")
public record WaveProperties(
        // TODO(Wave) : confirmer que les clés de test utilisent le même hôte que la production
        // (aucune URL de sandbox distincte n'est utilisée dans le code existant).
        String baseUrl,
        String payoutEndpointPath,
        Duration requestTimeout
) {
    public WaveProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.wave.com";
        }
        if (payoutEndpointPath == null || payoutEndpointPath.isBlank()) {
            payoutEndpointPath = "/v1/payout";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
    }
}
