package com.paylance.provider.credentials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifiants OAuth2 client_credentials Orange Money (API Sonatel) et code marchand.
 * {@code merchantName} n'est pas secret mais propre au marchand : il voyage avec le reste.
 */
public record OrangeMoneyCredentials(String clientId, String clientSecret, String merchantCode, String merchantName)
        implements ProviderCredentials {

    public OrangeMoneyCredentials {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
                || merchantCode == null || merchantCode.isBlank()) {
            throw new IllegalArgumentException("Les identifiants clientId, clientSecret et merchantCode sont obligatoires pour un compte Orange Money.");
        }
    }

    public static OrangeMoneyCredentials fromMap(Map<String, String> map) {
        return new OrangeMoneyCredentials(map.get("clientId"), map.get("clientSecret"),
                map.get("merchantCode"), map.get("merchantName"));
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("clientId", clientId);
        map.put("clientSecret", clientSecret);
        map.put("merchantCode", merchantCode);
        if (merchantName != null) map.put("merchantName", merchantName);
        return map;
    }

    @Override
    public Map<String, String> masked() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("clientId", ProviderCredentials.mask(clientId));
        map.put("clientSecret", ProviderCredentials.mask(clientSecret));
        map.put("merchantCode", merchantCode);
        if (merchantName != null) map.put("merchantName", merchantName);
        return map;
    }

    @Override
    public String toString() {
        return "OrangeMoneyCredentials" + masked();
    }
}
