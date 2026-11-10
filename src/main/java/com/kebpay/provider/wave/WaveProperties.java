package com.kebpay.provider.wave;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kebpay.provider.wave")
public record WaveProperties(
        String baseUrl,
        String checkoutApiKey,
        String payoutApiKey,
        String balanceApiKey,
        String webhookSecret,
        String successUrl,
        String errorUrl,
        String payoutEndpointPath
) {
    public String effectivePayoutApiKey() {
        return payoutApiKey != null && !payoutApiKey.isBlank() ? payoutApiKey : checkoutApiKey;
    }

    public String effectiveBalanceApiKey() {
        return balanceApiKey != null && !balanceApiKey.isBlank() ? balanceApiKey : effectivePayoutApiKey();
    }
}
