package com.paylance.common;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {

    private static final int MAX_LENGTH = 60;

    private Slugs() {
    }

    /** « Boutique Dakar Été » → {@code boutique-dakar-ete}. */
    public static String slugify(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH).replaceAll("-+$", "") : slug;
    }
}
