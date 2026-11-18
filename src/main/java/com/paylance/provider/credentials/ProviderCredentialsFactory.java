package com.paylance.provider.credentials;

import com.paylance.domain.enums.PaymentProvider;

import java.util.Map;
import java.util.Set;

public final class ProviderCredentialsFactory {

    static final int MAX_VALUE_LENGTH = 500;

    /** Clés acceptées par prestataire : toute autre clé est refusée, rien n'est stocké en silence. */
    private static final Map<PaymentProvider, Set<String>> ALLOWED_KEYS = Map.of(
            PaymentProvider.WAVE, Set.of("checkoutApiKey", "payoutApiKey", "balanceApiKey"),
            PaymentProvider.ORANGE_MONEY, Set.of("clientId", "clientSecret", "merchantCode", "merchantName"));

    private ProviderCredentialsFactory() {
    }

    /**
     * Construit (et valide) les identifiants typés d'un prestataire. Lève IllegalArgumentException,
     * avec un message affichable, si une clé manque, est inconnue ou trop longue.
     */
    public static ProviderCredentials from(PaymentProvider provider, Map<String, String> values) {
        if (values == null) {
            throw new IllegalArgumentException("Renseignez les identifiants du compte.");
        }
        Set<String> allowed = ALLOWED_KEYS.get(provider);
        values.forEach((key, value) -> {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Identifiant non reconnu pour ce prestataire : "
                        + (key == null ? "(vide)" : key.substring(0, Math.min(key.length(), 40))) + ".");
            }
            if (value != null && value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("L'identifiant " + key + " ne doit pas dépasser " + MAX_VALUE_LENGTH + " caractères.");
            }
        });
        return switch (provider) {
            case WAVE -> WaveCredentials.fromMap(values);
            case ORANGE_MONEY -> OrangeMoneyCredentials.fromMap(values);
        };
    }
}
