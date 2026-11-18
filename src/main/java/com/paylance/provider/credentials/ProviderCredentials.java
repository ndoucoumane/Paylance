package com.paylance.provider.credentials;

import java.util.Map;

/**
 * Identifiants d'un compte marchand chez un prestataire. Jamais persistés en clair
 * (voir {@code EncryptionService}) ni renvoyés par l'API (seulement {@link #masked()}).
 */
public sealed interface ProviderCredentials permits WaveCredentials, OrangeMoneyCredentials {

    /** Représentation clé/valeur, sérialisée puis chiffrée en base. */
    Map<String, String> toMap();

    /** Même structure que {@link #toMap()}, valeurs masquées (4 derniers caractères). */
    Map<String, String> masked();

    /** Format attendu par le front : quatre puces, puis les 4 derniers caractères. */
    String MASK = "\u2022\u2022\u2022\u2022";

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() <= 4) {
            return MASK;
        }
        return MASK + value.substring(value.length() - 4);
    }
}
