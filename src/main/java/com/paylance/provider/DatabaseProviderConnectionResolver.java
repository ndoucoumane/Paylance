package com.paylance.provider;

import com.paylance.domain.entity.Payment;
import com.paylance.service.ProviderAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Construit la connexion à partir du compte prestataire (tenant courant) rattaché au paiement. */
@Component
@RequiredArgsConstructor
public class DatabaseProviderConnectionResolver implements ProviderConnectionResolver {

    private final ProviderAccountService providerAccountService;

    @Override
    public ProviderConnection forPayment(Payment payment) {
        return forAccount(payment.getProviderAccountId());
    }

    @Override
    public ProviderConnection forAccount(String providerAccountId) {
        return providerAccountService.toConnection(providerAccountService.get(providerAccountId));
    }
}
