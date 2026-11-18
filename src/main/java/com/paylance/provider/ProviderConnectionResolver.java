package com.paylance.provider;

import com.paylance.domain.entity.Payment;

/**
 * Fournit la {@link ProviderConnection} (identifiants déchiffrés) du compte prestataire
 * rattaché à un paiement.
 */
public interface ProviderConnectionResolver {

    ProviderConnection forPayment(Payment payment);

    ProviderConnection forAccount(String providerAccountId);
}
