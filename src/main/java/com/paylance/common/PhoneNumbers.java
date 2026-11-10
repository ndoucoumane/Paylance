package com.paylance.common;

/** Normalisation minimale des numéros (clé d'unicité des clients finaux). */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * Retire espaces, tirets, points et parenthèses ; convertit le préfixe international {@code 00}
     * en {@code +}. Un numéro sans indicatif est conservé tel quel (le pays ne peut pas être deviné
     * sans ambiguïté).
     */
    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s.()\\-]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        return compact;
    }
}
