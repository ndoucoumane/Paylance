package com.kebpay.provider;

import com.kebpay.domain.enums.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Résout le client provider adapté à un {@link PaymentProvider} donné.
 * Ajouter un nouveau provider = implémenter {@link PaymentProviderClient} et
 * l'enregistrer comme bean Spring — aucune modification ailleurs n'est nécessaire.
 */
@Component
public class PaymentProviderRegistry {

    private final Map<PaymentProvider, PaymentProviderClient> clientsByProvider;

    public PaymentProviderRegistry(List<PaymentProviderClient> clients) {
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentProviderClient::getProvider, Function.identity()));
    }

    public PaymentProviderClient resolve(PaymentProvider provider) {
        PaymentProviderClient client = clientsByProvider.get(provider);
        if (client == null) {
            throw new IllegalStateException("Aucun client provider enregistré pour : " + provider);
        }
        return client;
    }
}
