package com.paylance.common;

/**
 * Chiffre de contrôle de Luhn — détecte les erreurs de transcription/saisie sur une référence
 * numérique (même algorithme que les numéros de carte bancaire).
 */
public final class LuhnUtil {

    private LuhnUtil() {
    }

    /**
     * Calcule le chiffre de contrôle à ajouter à la fin de {@code digits} pour que
     * l'ensemble (payload + chiffre de contrôle) soit valide au sens de Luhn.
     */
    public static int computeCheckDigit(String digits) {
        int sum = 0;
        boolean doubleDigit = true; // le chiffre le plus à droite du payload est doublé en premier
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return (10 - (sum % 10)) % 10;
    }
}
