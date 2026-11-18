package com.paylance.repository.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Recherche partielle sûre et tolérante :
 * <ul>
 *   <li>insensible à la casse et aux espaces (« 77 123 » trouve {@code +221771234567}) ;</li>
 *   <li>les jokers SQL saisis par l'utilisateur ({@code %}, {@code _}, {@code \}) sont échappés :
 *   ils sont cherchés littéralement et ne transforment pas la recherche en balayage de table ;</li>
 *   <li>longueur bornée.</li>
 * </ul>
 * La valeur est toujours passée en paramètre lié par JPA, jamais concaténée au SQL.
 */
public final class SearchPatterns {

    static final int MAX_LENGTH = 100;
    private static final char ESCAPE = '\\';

    private SearchPatterns() {
    }

    /** Motif {@code %terme%} normalisé, ou {@code null} si la saisie est vide. */
    public static String containsPattern(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.replaceAll("\\s+", "").toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_LENGTH) {
            normalized = normalized.substring(0, MAX_LENGTH);
        }
        StringBuilder escaped = new StringBuilder("%");
        for (char c : normalized.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.append('%').toString();
    }

    /** {@code replace(lower(champ), ' ', '') LIKE motif ESCAPE '\'}. */
    public static Predicate matches(CriteriaBuilder cb, Expression<String> field, String pattern) {
        Expression<String> normalized = cb.function("replace", String.class, cb.lower(field), cb.literal(" "), cb.literal(""));
        return cb.like(normalized, pattern, ESCAPE);
    }
}
