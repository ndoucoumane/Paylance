package com.paylance.web.dto.onboarding;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code credentials} :
 * <ul>
 *   <li>WAVE : {@code checkoutApiKey} (requis), {@code payoutApiKey}, {@code balanceApiKey} ;</li>
 *   <li>ORANGE_MONEY : {@code clientId}, {@code clientSecret}, {@code merchantCode} (requis), {@code merchantName}.</li>
 * </ul>
 * {@code webhookSecret} : Wave → secret de signature fourni par Wave ; Orange Money → apiKey
 * choisie par le marchand et déclarée pour les notifications.
 */
public record ProviderAccountCreateRequest(
        @NotNull(message = "Choisissez le prestataire.") PaymentProvider provider,
        @NotBlank(message = "Donnez un nom à ce compte.")
        @Size(max = 150, message = "Le nom du compte ne doit pas dépasser 150 caractères.") String label,
        @NotNull(message = "Choisissez l'environnement (test ou production).") EnvironmentType environment,
        @NotEmpty(message = "Renseignez les identifiants du compte.")
        @Size(max = 10, message = "Trop d'identifiants envoyés.") Map<String, String> credentials,
        @Size(max = 500, message = "La clé de signature ne doit pas dépasser 500 caractères.") String webhookSecret
) {
}
