package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Pays et devise sont aussi contrôlés contre les listes fermées du serveur (OrganizationService). */
public record OrganizationCreateRequest(
        @NotBlank(message = "Indiquez le nom de votre entreprise.")
        @Size(min = 2, max = 150, message = "Le nom de l'entreprise doit contenir entre 2 et 150 caractères.")
        String name,

        @Size(max = 60, message = "L'identifiant ne doit pas dépasser 60 caractères.")
        @Pattern(regexp = "^[a-z0-9-]*$", message = "L'identifiant ne peut contenir que des minuscules, des chiffres et des tirets.")
        String slug,

        @NotBlank(message = "Choisissez le pays de votre entreprise.")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "Le pays doit être un code à 2 lettres (ex. SN).")
        String country,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "La devise doit être un code à 3 lettres (ex. XOF).")
        String defaultCurrency
) {
}
