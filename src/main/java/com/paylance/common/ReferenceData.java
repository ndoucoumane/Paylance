package com.paylance.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listes fermées partagées avec le front (voir docs/contrat-api-securite.md). Le serveur valide
 * contre ces listes, jamais contre ce que le client prétend accepter.
 */
public final class ReferenceData {

    /** Pays proposés à l'inscription d'une organisation, et leur devise par défaut. */
    private static final Map<String, String> COUNTRY_CURRENCY = Map.of(
            "SN", "XOF", "CI", "XOF", "ML", "XOF", "BF", "XOF", "BJ", "XOF", "TG", "XOF", "NE", "XOF",
            "GN", "GNF",
            "CM", "XAF",
            "FR", "EUR");

    public static final Set<String> COUNTRIES = COUNTRY_CURRENCY.keySet();
    public static final List<String> CURRENCIES = List.of("XOF", "XAF", "GNF", "EUR", "USD");

    private ReferenceData() {
    }

    public static boolean isSupportedCountry(String country) {
        return country != null && COUNTRY_CURRENCY.containsKey(country);
    }

    public static boolean isSupportedCurrency(String currency) {
        return currency != null && CURRENCIES.contains(currency);
    }

    /** Devise par défaut d'un pays pris en charge. */
    public static String defaultCurrencyFor(String country) {
        String currency = COUNTRY_CURRENCY.get(country);
        if (currency == null) {
            throw new IllegalArgumentException("Pays non pris en charge : " + country);
        }
        return currency;
    }
}
