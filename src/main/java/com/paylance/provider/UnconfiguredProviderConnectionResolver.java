package com.paylance.provider;

import com.paylance.domain.entity.Payment;
import com.paylance.web.exception.ProviderAccountNotConfiguredException;
import org.springframework.stereotype.Component;

/**
 * Résolveur provisoire : les identifiants globaux ont été retirés et les comptes prestataires
 * par client n'existent pas encore. Toute opération prestataire est donc refusée proprement.
 */
@Component
public class UnconfiguredProviderConnectionResolver implements ProviderConnectionResolver {

    @Override
    public ProviderConnection forPayment(Payment payment) {
        throw new ProviderAccountNotConfiguredException("Aucun compte prestataire configuré");
    }

    @Override
    public ProviderConnection forAccount(String providerAccountId) {
        throw new ProviderAccountNotConfiguredException("Compte prestataire introuvable : " + providerAccountId);
    }
}
