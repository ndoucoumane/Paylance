package com.paylance.provider.credentials;

import com.paylance.domain.enums.PaymentProvider;

import java.util.Map;

public final class ProviderCredentialsFactory {

    private ProviderCredentialsFactory() {
    }

    /** Construit (et valide) les identifiants typés d'un prestataire ; lève IllegalArgumentException si incomplets. */
    public static ProviderCredentials from(PaymentProvider provider, Map<String, String> values) {
        if (values == null) {
            throw new IllegalArgumentException("credentials est requis");
        }
        return switch (provider) {
            case WAVE -> WaveCredentials.fromMap(values);
            case ORANGE_MONEY -> OrangeMoneyCredentials.fromMap(values);
        };
    }
}
