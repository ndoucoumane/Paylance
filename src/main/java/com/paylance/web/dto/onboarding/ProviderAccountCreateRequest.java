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
        @NotNull PaymentProvider provider,
        @NotBlank @Size(max = 150) String label,
        @NotNull EnvironmentType environment,
        @NotEmpty Map<String, String> credentials,
        @Size(max = 500) String webhookSecret
) {
}
