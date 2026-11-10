package com.kebpay.provider.wave;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kebpay.domain.entity.Payment;
import com.kebpay.provider.ProviderInitiateResult;
import com.kebpay.provider.ProviderStatusResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Frappe le vrai endpoint Wave (WAVE_BASE_URL / WAVE_CHECKOUT_API_KEY de l'environnement) avec les
 * credentials de test du compte Developer Wave — ignoré (assumption skip, pas d'échec) si
 * ces variables sont absentes ou valent le placeholder par défaut, pour ne jamais casser un
 * `mvn test` local ou en CI sans accès Wave.
 *
 * Suffixe {@code IT} volontaire (cf. {@link com.kebpay.KebpayApplicationIT}) : n'est jamais
 * ramassé par le pattern par défaut de Surefire (`**&#47;*Test.java`), donc jamais exécuté par
 * {@code mvn test} / {@code make test}. Exécution explicite :
 * {@code set -a && . ./.env && set +a && mvn test -Dtest=WaveSandboxIT}
 *
 * Chaque test crée sa propre session de checkout Wave (aucun état partagé entre tests) ;
 * comme personne ne complète le paiement via le lien renvoyé, aucun fonds ne bouge réellement.
 */
class WaveSandboxIT {

    private static WaveClient waveClient;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("WAVE_CHECKOUT_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank() && !"change-me".equals(apiKey),
                "WAVE_CHECKOUT_API_KEY absent ou placeholder — test sandbox Wave ignoré");

        WaveProperties properties = new WaveProperties(
                envOrDefault("WAVE_BASE_URL", "https://api.wave.com"),
                apiKey,
                envOrDefault("WAVE_PAYOUT_API_KEY", apiKey),
                envOrDefault("WAVE_BALANCE_API_KEY", apiKey),
                System.getenv("WAVE_WEBHOOK_SECRET"),
                envOrDefault("WAVE_SUCCESS_URL", "https://kebpay.internal/wave/success"),
                envOrDefault("WAVE_ERROR_URL", "https://kebpay.internal/wave/error"),
                envOrDefault("WAVE_PAYOUT_ENDPOINT_PATH", "/v1/payout"));

        waveClient = new WaveClient(WebClient.builder(), properties, new ObjectMapper());
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Payment newSandboxPayment() {
        return Payment.builder()
                .id("kebpay-sandbox-" + System.currentTimeMillis())
                .amount(100000L) // 1000 XOF, en centimes
                .currency("XOF")
                .build();
    }

    @Test
    void initiate_createsRealCheckoutSession() {
        ProviderInitiateResult result = waveClient.initiate(newSandboxPayment());

        assertThat(result.providerTransactionId()).isNotBlank();
        assertThat(result.checkoutReference()).startsWith("https://");
        assertThat(result.status()).isNotNull();
        assertThat(result.rawResponse()).contains(result.providerTransactionId());
    }

    @Test
    void checkStatus_reflectsFreshlyCreatedOpenSession() {
        ProviderInitiateResult initiateResult = waveClient.initiate(newSandboxPayment());

        Payment toCheck = Payment.builder()
                .providerTransactionId(initiateResult.providerTransactionId())
                .build();
        ProviderStatusResult status = waveClient.checkStatus(toCheck);

        assertThat(status.status()).isNotNull();
        assertThat(status.rawResponse()).contains(initiateResult.providerTransactionId());
    }
}
