package com.paylance.provider;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.provider.credentials.ProviderCredentials;

/**
 * Tout ce dont un client prestataire a besoin pour agir au nom d'UN compte marchand :
 * identifiants déchiffrés, secret webhook, environnement. Construit à la volée à partir d'un
 * {@code ProviderAccount}, jamais mis en cache ni loggé (toString masqué).
 */
public record ProviderConnection(
        String providerAccountId,
        PaymentProvider provider,
        EnvironmentType environment,
        ProviderCredentials credentials,
        /** Wave : secret HMAC du webhook. Orange Money : apiKey déclarée pour les callbacks. */
        String webhookSecret
) {

    public <T extends ProviderCredentials> T credentials(Class<T> type) {
        if (!type.isInstance(credentials)) {
            throw new IllegalStateException("Le compte " + providerAccountId + " (" + provider
                    + ") n'a pas d'identifiants de type " + type.getSimpleName());
        }
        return type.cast(credentials);
    }

    @Override
    public String toString() {
        return "ProviderConnection[providerAccountId=" + providerAccountId + ", provider=" + provider
                + ", environment=" + environment + "]";
    }
}
