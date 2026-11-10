package com.kebpay.provider.orangemoney;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kebpay.provider.orange-money")
public record OrangeMoneyProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String merchantCode,
        String merchantName,
        /** Valeur choisie par KebPay (pas fournie par Orange), renvoyée telle quelle par Sonatel
         * dans l'en-tête {@code Authorization: Basic <callbackApiKey>} de chaque callback reçu —
         * sert à authentifier l'origine du webhook. */
        String callbackApiKey,
        String successRedirectUrl,
        String cancelRedirectUrl,
        Integer qrCodeValiditySeconds,
        String refundEndpointPath,
        String payoutEndpointPath
) {
}
