package com.kebpay.web.dto;

import com.kebpay.domain.enums.PaymentProvider;

/**
 * Solde du wallet d'un provider de paiement.
 *
 * @param amount   montant en unité principale (ex: 1500 = 1 500 XOF)
 * @param currency devise ISO (ex: "XOF")
 * @param provider provider interrogé
 */
public record WalletBalanceResponse(
        String amount,
        String currency,
        PaymentProvider provider
) {
}
