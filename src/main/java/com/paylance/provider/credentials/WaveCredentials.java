package com.paylance.provider.credentials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clés API Wave du marchand. Wave émet des clés scopées par produit : checkout (encaissement),
 * payout (versement), balance (consultation du solde). Les clés payout/balance sont optionnelles
 * et retombent sur la clé checkout.
 */
public record WaveCredentials(String checkoutApiKey, String payoutApiKey, String balanceApiKey)
        implements ProviderCredentials {

    public WaveCredentials {
        if (checkoutApiKey == null || checkoutApiKey.isBlank()) {
            throw new IllegalArgumentException("checkoutApiKey est requis pour un compte Wave");
        }
    }

    public String effectivePayoutApiKey() {
        return payoutApiKey != null && !payoutApiKey.isBlank() ? payoutApiKey : checkoutApiKey;
    }

    public String effectiveBalanceApiKey() {
        return balanceApiKey != null && !balanceApiKey.isBlank() ? balanceApiKey : effectivePayoutApiKey();
    }

    public static WaveCredentials fromMap(Map<String, String> map) {
        return new WaveCredentials(map.get("checkoutApiKey"), map.get("payoutApiKey"), map.get("balanceApiKey"));
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("checkoutApiKey", checkoutApiKey);
        if (payoutApiKey != null) map.put("payoutApiKey", payoutApiKey);
        if (balanceApiKey != null) map.put("balanceApiKey", balanceApiKey);
        return map;
    }

    @Override
    public Map<String, String> masked() {
        Map<String, String> map = new LinkedHashMap<>();
        toMap().forEach((k, v) -> map.put(k, ProviderCredentials.mask(v)));
        return map;
    }

    @Override
    public String toString() {
        return "WaveCredentials" + masked();
    }
}
