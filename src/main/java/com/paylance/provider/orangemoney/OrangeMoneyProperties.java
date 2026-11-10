package com.paylance.provider.orangemoney;

import com.paylance.domain.enums.EnvironmentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration globale NON secrète de l'intégration Orange Money (API Sonatel). Les identifiants
 * OAuth, le code marchand et l'apiKey de callback appartiennent à chaque compte marchand.
 */
@ConfigurationProperties(prefix = "paylance.provider.orange-money")
public record OrangeMoneyProperties(
        String liveBaseUrl,
        String testBaseUrl,
        Integer qrCodeValiditySeconds,
        String refundEndpointPath,
        String payoutEndpointPath,
        Duration requestTimeout
) {
    public OrangeMoneyProperties {
        if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
            liveBaseUrl = "https://api.orange-sonatel.com";
        }
        if (testBaseUrl == null || testBaseUrl.isBlank()) {
            testBaseUrl = "https://api.sandbox.orange-sonatel.com";
        }
        if (qrCodeValiditySeconds == null) {
            qrCodeValiditySeconds = 300;
        }
        if (payoutEndpointPath == null || payoutEndpointPath.isBlank()) {
            payoutEndpointPath = "/api/eWallet/v1/cashins";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
    }

    public String baseUrl(EnvironmentType environment) {
        return environment == EnvironmentType.LIVE ? liveBaseUrl : testBaseUrl;
    }
}
