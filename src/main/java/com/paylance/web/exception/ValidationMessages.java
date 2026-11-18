package com.paylance.web.exception;

import jakarta.validation.ConstraintViolation;
import org.springframework.validation.FieldError;

import java.util.Map;

/**
 * Message d'une erreur de validation, lisible par un commerçant.
 *
 * <p>Si la contrainte du DTO porte un message personnalisé ({@code message = "..."}), il est
 * repris tel quel : c'est là qu'on écrit la phrase la plus précise. Sinon, un message générique
 * en français est construit selon le type de contrainte, plutôt que le message par défaut de
 * Hibernate Validator (dont la langue dépend de la JVM).
 */
final class ValidationMessages {

    private ValidationMessages() {
    }

    static String of(FieldError error) {
        String field = "« " + error.getField() + " »";
        ConstraintViolation<?> violation = unwrap(error);
        if (violation == null) {
            // Erreur de conversion (type incompatible) : pas de contrainte associée.
            return "Le champ " + field + " a une valeur invalide.";
        }
        String template = violation.getMessageTemplate();
        if (template != null && !template.startsWith("{")) {
            return violation.getMessage(); // message personnalisé du DTO
        }
        Map<String, Object> attributes = violation.getConstraintDescriptor().getAttributes();
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "Le champ " + field + " est obligatoire.";
            case "Size" -> sizeMessage(field, attributes);
            case "Positive" -> "Le champ " + field + " doit être supérieur à 0.";
            case "PositiveOrZero" -> "Le champ " + field + " ne peut pas être négatif.";
            case "Min" -> "Le champ " + field + " doit être au moins " + attributes.get("value") + ".";
            case "Max" -> "Le champ " + field + " doit être au plus " + attributes.get("value") + ".";
            case "Email" -> "Le champ " + field + " doit être une adresse e-mail valide.";
            case "Pattern" -> "Le champ " + field + " n'a pas le format attendu.";
            case "Future", "FutureOrPresent" -> "Le champ " + field + " doit être une date future.";
            case "AssertTrue" -> "Le champ " + field + " doit être accepté.";
            default -> "Le champ " + field + " est invalide.";
        };
    }

    private static String sizeMessage(String field, Map<String, Object> attributes) {
        int min = (Integer) attributes.getOrDefault("min", 0);
        int max = (Integer) attributes.getOrDefault("max", Integer.MAX_VALUE);
        if (min > 0 && max < Integer.MAX_VALUE) {
            return "Le champ " + field + " doit contenir entre " + min + " et " + max + " caractères.";
        }
        if (max < Integer.MAX_VALUE) {
            return "Le champ " + field + " doit contenir au plus " + max + " caractères.";
        }
        return "Le champ " + field + " doit contenir au moins " + min + " caractères.";
    }

    private static ConstraintViolation<?> unwrap(FieldError error) {
        try {
            return error.unwrap(ConstraintViolation.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
