package com.paylance.provider.wave;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.common.HmacUtil;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.ProviderInitiateResult;
import com.paylance.provider.ProviderPayoutResult;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.provider.ProviderStatusResult;
import com.paylance.provider.ConnectionTestResult;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.WebhookParseResult;
import com.paylance.provider.credentials.WaveCredentials;
import com.paylance.web.exception.ProviderCommunicationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Intégration Wave Checkout API.
 * Référence : https://docs.wave.com/business/api (checkout sessions).
 * Les endpoints/champs sont basés sur la documentation publique et doivent être
 * confirmés/ajustés avec les credentials réels avant mise en production.
 */
@Slf4j
@Component
public class WaveClient implements PaymentProviderClient {

    private static final Duration WAVE_SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final WebClient webClient;
    private final WaveProperties properties;
    private final ObjectMapper objectMapper;

    public WaveClient(WebClient.Builder webClientBuilder, WaveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.WAVE;
    }

    @Override
    public ProviderInitiateResult initiate(ProviderConnection connection, Payment payment) {
        WaveCredentials credentials = connection.credentials(WaveCredentials.class);
        long start = System.currentTimeMillis();
        log.info("[WAVE_INITIATE_START] Initiation session checkout Wave pour paymentId={}, montant={} {}",
                payment.getId(), toWaveAmount(payment.getAmount()), payment.getCurrency());
        WaveCheckoutSessionRequest request = new WaveCheckoutSessionRequest(
                toWaveAmount(payment.getAmount()),
                payment.getCurrency(),
                payment.getId(),
                payment.getSuccessUrl(),
                payment.getErrorUrl()
        );
        try {
            WaveCheckoutSessionResponse response = webClient.post()
                    .uri("/v1/checkout/sessions")
                    .headers(h -> h.setBearerAuth(credentials.checkoutApiKey()))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(WaveCheckoutSessionResponse.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("[WAVE_INITIATE_EMPTY] Réponse vide de Wave après {}ms pour paymentId={}", duration, payment.getId());
                throw new ProviderCommunicationException("Réponse vide de Wave lors de l'initiation du checkout");
            }
            log.info("[WAVE_INITIATE_SUCCESS] Session checkout Wave créée avec succès en {}ms pour paymentId={} (waveId={}, checkoutStatus={}, paymentStatus={})",
                    duration, payment.getId(), response.id(), response.checkoutStatus(), response.paymentStatus());
            return new ProviderInitiateResult(
                    response.id(),
                    response.waveLaunchUrl(),
                    mapCheckoutSessionStatus(response.checkoutStatus(), response.paymentStatus()),
                    writeRaw(response)
            );
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_INITIATE_ERROR] Erreur Wave initiate après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de l'initiation du paiement Wave : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_INITIATE_FAILED] Exception lors de l'appel Wave initiate après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderStatusResult checkStatus(ProviderConnection connection, Payment payment) {
        WaveCredentials credentials = connection.credentials(WaveCredentials.class);
        if (payment.getProviderTransactionId() == null || payment.getProviderTransactionId().isBlank()) {
            if (payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(LocalDateTime.now())) {
                return new ProviderStatusResult(PaymentStatus.EXPIRED, null, "Paiement expiré sans session Wave", null);
            }
            return new ProviderStatusResult(PaymentStatus.FAILED, null, "Aucun identifiant de session Wave", null);
        }

        if (payment.getProviderTransactionId().startsWith("po-") || payment.getProviderTransactionId().startsWith("payout-")) {
            return checkPayoutStatus(credentials, payment);
        }

        long start = System.currentTimeMillis();
        log.info("[WAVE_CHECK_STATUS_START] Vérification statut Wave pour paymentId={}, transactionId={}",
                payment.getId(), payment.getProviderTransactionId());
        try {
            WaveCheckoutSessionResponse response = webClient.get()
                    .uri("/v1/checkout/sessions/{id}", payment.getProviderTransactionId())
                    .headers(h -> h.setBearerAuth(credentials.checkoutApiKey()))
                    .retrieve()
                    .bodyToMono(WaveCheckoutSessionResponse.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("[WAVE_CHECK_STATUS_EMPTY] Réponse vide de Wave après {}ms pour transactionId={}", duration, payment.getProviderTransactionId());
                throw new ProviderCommunicationException("Réponse vide de Wave lors de la vérification du statut");
            }
            log.info("[WAVE_CHECK_STATUS_SUCCESS] Statut Wave récupéré en {}ms pour transactionId={} (checkoutStatus={}, paymentStatus={})",
                    duration, payment.getProviderTransactionId(), response.checkoutStatus(), response.paymentStatus());
            // Filet de sécurité : permet de détecter une session expirée même si
            // le webhook checkout.session.expired n'a jamais été reçu.
            PaymentStatus status = mapCheckoutSessionStatus(
                    response.checkoutStatus(),
                    response.paymentStatus()
            );
            String failureReason = extractFailureReason(response.paymentStatus(), null);
            return new ProviderStatusResult(status, writeRaw(response), failureReason, null);
        } catch (WebClientResponseException.NotFound e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[WAVE_CHECK_STATUS_404] Session Wave introuvable (404) après {}ms pour transactionId={}",
                    duration, payment.getProviderTransactionId());
            if (payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(LocalDateTime.now())) {
                return new ProviderStatusResult(PaymentStatus.EXPIRED, e.getResponseBodyAsString(), "Session Wave introuvable (expirée)", null);
            }
            return new ProviderStatusResult(PaymentStatus.FAILED, e.getResponseBodyAsString(), "Session Wave introuvable chez le provider", null);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_CHECK_STATUS_ERROR] Erreur Wave checkStatus après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de la vérification du statut Wave : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_CHECK_STATUS_FAILED] Exception Wave checkStatus après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    private ProviderStatusResult checkPayoutStatus(WaveCredentials credentials, Payment payment) {
        long start = System.currentTimeMillis();
        log.info("[WAVE_CHECK_PAYOUT_STATUS_START] Vérification statut Payout Wave pour paymentId={}, payoutId={}",
                payment.getId(), payment.getProviderTransactionId());
        try {
            WavePayoutResponse response = webClient.get()
                    .uri("/v1/payout/{id}", payment.getProviderTransactionId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.effectivePayoutApiKey())
                    .retrieve()
                    .bodyToMono(WavePayoutResponse.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("[WAVE_CHECK_PAYOUT_STATUS_EMPTY] Réponse vide de Wave Payout après {}ms pour payoutId={}",
                        duration, payment.getProviderTransactionId());
                throw new ProviderCommunicationException("Réponse vide de Wave lors de la vérification du statut de virement");
            }
            log.info("[WAVE_CHECK_PAYOUT_STATUS_SUCCESS] Statut Payout Wave récupéré en {}ms pour payoutId={} (status={})",
                    duration, payment.getProviderTransactionId(), response.status());

            PaymentStatus status = mapPayoutStatus(response.status());
            String failureReason = response.payoutError() != null ? response.payoutError() : response.errorMessage();
            return new ProviderStatusResult(status, writeRaw(response), failureReason, null);
        } catch (WebClientResponseException.NotFound e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[WAVE_CHECK_PAYOUT_STATUS_404] Payout Wave introuvable (404) après {}ms pour payoutId={}",
                    duration, payment.getProviderTransactionId());
            return new ProviderStatusResult(PaymentStatus.FAILED, e.getResponseBodyAsString(), "Virement Wave introuvable chez le provider", null);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_CHECK_PAYOUT_STATUS_ERROR] Erreur Wave checkPayoutStatus après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de la vérification du statut de virement Wave : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_CHECK_PAYOUT_STATUS_FAILED] Exception Wave checkPayoutStatus après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderRefundResult refund(ProviderConnection connection, Payment payment, Long amount, String reason) {
        WaveCredentials credentials = connection.credentials(WaveCredentials.class);
        // TODO(Wave) : le remboursement partiel d'une checkout session est-il possible ? Le code
        // historique le considère impossible et refuse localement tout montant partiel.
        if (amount != null && amount < payment.getAmount()) {
            return new ProviderRefundResult(null, RefundStatus.FAILED, null,
                    "Wave ne supporte que le remboursement total d'une checkout session");
        }
        long start = System.currentTimeMillis();
        log.info("[WAVE_REFUND_START] Demande de remboursement Wave pour paymentId={}, transactionId={}, amount={}",
                payment.getId(), payment.getProviderTransactionId(), amount);
        try {
            var response = webClient.post()
                    .uri("/v1/checkout/sessions/{id}/refund", payment.getProviderTransactionId())
                    .headers(h -> h.setBearerAuth(credentials.checkoutApiKey()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            log.info("[WAVE_REFUND_SUCCESS] Remboursement Wave exécuté en {}ms pour transactionId={}",
                    duration, payment.getProviderTransactionId());
            return new ProviderRefundResult(payment.getProviderTransactionId(), RefundStatus.SUCCESS, response, null);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_REFUND_ERROR] Erreur Wave refund après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            return new ProviderRefundResult(null, RefundStatus.FAILED, e.getResponseBodyAsString(), e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_REFUND_FAILED] Exception Wave refund après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderPayoutResult payout(ProviderConnection connection, Payment payment) {
        WaveCredentials credentials = connection.credentials(WaveCredentials.class);
        long start = System.currentTimeMillis();
        log.info("[WAVE_PAYOUT_START] Initiation virement direct (Payout) Wave pour paymentId={}, montant={} {}, destinataire={}",
                payment.getId(), toWaveAmount(payment.getAmount()), payment.getCurrency(), payment.getPayerPhone());

        String payoutPath = properties.payoutEndpointPath();

        WavePayoutRequest request = new WavePayoutRequest(
                toWaveAmount(payment.getAmount()),
                payment.getCurrency(),
                payment.getPayerPhone(),
                payment.getPayerName(),
                payment.getId()
        );

        String idempotencyKey = payment.getIdempotencyKey() != null && !payment.getIdempotencyKey().isBlank()
                ? payment.getIdempotencyKey()
                : payment.getId();

        try {
            WavePayoutResponse response = webClient.post()
                    .uri(payoutPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.effectivePayoutApiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(WavePayoutResponse.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("[WAVE_PAYOUT_EMPTY] Réponse vide de Wave Payout après {}ms pour paymentId={}", duration, payment.getId());
                throw new ProviderCommunicationException("Réponse vide de Wave lors du versement direct");
            }

            PaymentStatus status = mapPayoutStatus(response.status());
            String failureReason = response.payoutError() != null ? response.payoutError() : response.errorMessage();
            log.info("[WAVE_PAYOUT_SUCCESS] Payout Wave exécuté avec succès en {}ms pour paymentId={} (payoutId={}, status={})",
                    duration, payment.getId(), response.id(), status);

            return new ProviderPayoutResult(response.id(), status, writeRaw(response), failureReason);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            String responseBody = e.getResponseBodyAsString();
            log.error("[WAVE_PAYOUT_ERROR] Erreur Wave payout après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), responseBody);
            return new ProviderPayoutResult(null, PaymentStatus.FAILED, responseBody,
                    responseBody != null && !responseBody.isBlank() ? responseBody : e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_PAYOUT_FAILED] Exception lors de l'appel Wave payout après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Consulte le solde du wallet WAVE via {@code GET /v1/balance}.
     * Utilise la {@code balanceApiKey} (scope balance_api) pour l'authentification.
     */
    public WaveBalanceResponse getBalance(ProviderConnection connection) {
        WaveCredentials credentials = connection.credentials(WaveCredentials.class);
        long start = System.currentTimeMillis();
        log.info("[WAVE_BALANCE_START] Consultation du solde Wave");
        try {
            WaveBalanceResponse response = webClient.get()
                    .uri("/v1/balance")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.effectiveBalanceApiKey())
                    .retrieve()
                    .bodyToMono(WaveBalanceResponse.class)
                    .timeout(properties.requestTimeout())
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("[WAVE_BALANCE_EMPTY] Réponse vide de Wave Balance après {}ms", duration);
                throw new ProviderCommunicationException("Réponse vide de Wave lors de la consultation du solde");
            }

            log.info("[WAVE_BALANCE_SUCCESS] Solde Wave récupéré en {}ms: {} {}",
                    duration, response.amount(), response.currency());
            return response;
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_BALANCE_ERROR] Erreur Wave balance après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Erreur lors de la consultation du solde Wave : " + e.getStatusCode());
        } catch (ProviderCommunicationException e) {
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[WAVE_BALANCE_FAILED] Exception lors de la consultation du solde Wave après {}ms: {}",
                    duration, e.getMessage(), e);
            throw new ProviderCommunicationException("Erreur lors de la consultation du solde Wave : " + e.getMessage());
        }
    }

    /**
     * Lecture seule : {@code GET /v1/balance}, déjà utilisé pour la consultation du solde.
     * TODO(Wave) : une clé limitée au scope checkout_api peut recevoir un 403 sur cet endpoint
     * alors qu'elle est valide pour l'encaissement — à confirmer, et le cas échéant exiger une
     * clé balance_api à la création du compte.
     */
    @Override
    public ConnectionTestResult testConnection(ProviderConnection connection) {
        try {
            WaveBalanceResponse balance = getBalance(connection);
            return new ConnectionTestResult(true, "Connexion Wave valide (devise du compte : " + balance.currency() + ")");
        } catch (ProviderCommunicationException e) {
            return new ConnectionTestResult(false, e.getMessage());
        }
    }

    private PaymentStatus mapPayoutStatus(String wavePayoutStatus) {
        if (wavePayoutStatus == null) {
            return PaymentStatus.PROCESSING;
        }
        return switch (wavePayoutStatus.toLowerCase()) {
            case "succeeded", "success" -> PaymentStatus.SUCCESS;
            case "failed", "cancelled" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PROCESSING;
        };
    }

    /**
     * Format Wave : header {@code Wave-Signature: t={timestamp},v1={signature}}, où la valeur
     * signée est {@code "{timestamp}{payload_brut}"} (concaténation directe, sans séparateur).
     * Le timestamp doit aussi être récent (5 min) pour bloquer le rejeu d'une notification interceptée.
     * Référence : https://docs.wave.com/webhook
     */
    @Override
    public boolean verifyWebhookSignature(ProviderConnection connection, String rawPayload, String signatureHeader) {
        if (connection.webhookSecret() == null || connection.webhookSecret().isBlank()) {
            log.warn("[WAVE_SIGNATURE_NO_SECRET] Aucun secret webhook configuré pour le compte {}", connection.providerAccountId());
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("[WAVE_SIGNATURE_EMPTY] Header Wave-Signature manquant ou vide");
            return false;
        }
        String timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0].trim()) {
                case "t" -> timestamp = kv[1].trim();
                case "v1" -> signature = kv[1].trim();
                default -> { }
            }
        }
        if (timestamp == null || signature == null) {
            log.warn("[WAVE_SIGNATURE_INVALID_FORMAT] Header Wave-Signature malformé: {}", signatureHeader);
            return false;
        }
        try {
            long ageSeconds = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            if (ageSeconds > WAVE_SIGNATURE_TOLERANCE.toSeconds()) {
                log.warn("[WAVE_SIGNATURE_EXPIRED] Signature Wave expirée (age: {}s, tolérance: {}s)",
                        ageSeconds, WAVE_SIGNATURE_TOLERANCE.toSeconds());
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("[WAVE_SIGNATURE_TIMESTAMP_INVALID] Timestamp Wave non numérique: {}", timestamp);
            return false;
        }
        boolean valid = HmacUtil.matches(timestamp + rawPayload, connection.webhookSecret(), signature);
        log.info("[WAVE_SIGNATURE_CHECK] Vérification signature Wave: {}", valid ? "VALIDE" : "INVALIDE");
        return valid;
    }

    @Override
    public WebhookParseResult parseWebhookEvent(String rawPayload) {
        try {
            var root = objectMapper.readTree(rawPayload);
            var data = root.has("data") ? root.get("data") : root;
            String eventId = root.hasNonNull("id") ? root.get("id").asText() : null;
            String eventType = root.hasNonNull("type") ? root.get("type").asText() : null;

            log.info("[WAVE_WEBHOOK_PARSE] Événement Wave reçu: eventId={}, eventType={}", eventId, eventType);

            if ("merchant.payment_received".equals(eventType)) {
                return parseMerchantPaymentReceived(eventId, data);
            }

            String transactionId = data.hasNonNull("id") ? data.get("id").asText() : null;
            String checkoutStatus = data.hasNonNull("checkout_status") ? data.get("checkout_status").asText() : null;
            String paymentStatus = data.hasNonNull("payment_status") ? data.get("payment_status").asText() : null;
            PaymentStatus status = mapWebhookStatus(eventType, checkoutStatus, paymentStatus);
            String failureReason = extractFailureReason(paymentStatus, data.get("last_payment_error"));
            log.info("[WAVE_WEBHOOK_PARSED] Événement Wave mappé: transactionId={}, checkoutStatus={}, paymentStatus={}, finalStatus={}",
                    transactionId, checkoutStatus, paymentStatus, status);
            return new WebhookParseResult(eventId, transactionId, status, failureReason, null);
        } catch (Exception e) {
            log.error("[WAVE_WEBHOOK_PARSE_ERROR] Impossible de parser le webhook Wave: {}", e.getMessage());
            throw new ProviderCommunicationException("Impossible de parser le webhook Wave", e);
        }
    }

    /**
     * Paiement reçu directement sur le compte marchand (ex: lien de paiement Wave généré et
     * partagé manuellement à un client, sans passer par le Checkout API) : il n'existe donc
     * aucun {@code Payment} Paylance préexistant à retrouver par {@code providerTransactionId}.
     * Toujours {@link PaymentStatus#SUCCESS} — Wave n'émet cet événement qu'une fois l'argent
     * effectivement crédité, contrairement aux événements de checkout session.
     */
    private WebhookParseResult parseMerchantPaymentReceived(
            String eventId, com.fasterxml.jackson.databind.JsonNode data) {
        String transactionId = data.hasNonNull("id") ? data.get("id").asText() : null;
        String senderMobile = data.hasNonNull("sender_mobile") ? data.get("sender_mobile").asText() : null;
        String currency = data.hasNonNull("currency") ? data.get("currency").asText() : "XOF";
        Long amountInCents = data.hasNonNull("amount") ? toCents(data.get("amount").asText()) : null;
        var unattributed = new WebhookParseResult.UnattributedPayment(amountInCents, currency, senderMobile);
        return new WebhookParseResult(eventId, transactionId, PaymentStatus.SUCCESS, null, unattributed);
    }

    /** Inverse de {@link #toWaveAmount} : Wave envoie l'unité principale, Paylance stocke en centimes. */
    private Long toCents(String amount) {
        return new BigDecimal(amount).movePointRight(2).longValueExact();
    }

    /**
     * Wave recommande de valider {@code checkout_status} et {@code payment_status} ensemble :
     * succès uniquement si {@code complete + succeeded}.
     */
    private PaymentStatus mapCheckoutSessionStatus(String checkoutStatus, String paymentStatus) {
        if (checkoutStatus != null && "expired".equalsIgnoreCase(checkoutStatus)) {
            return PaymentStatus.EXPIRED;
        }
        if (checkoutStatus != null && "complete".equalsIgnoreCase(checkoutStatus)) {
            if (paymentStatus != null && "succeeded".equalsIgnoreCase(paymentStatus)) {
                return PaymentStatus.SUCCESS;
            }
            if (paymentStatus != null && ("cancelled".equalsIgnoreCase(paymentStatus) || "failed".equalsIgnoreCase(paymentStatus))) {
                return PaymentStatus.FAILED;
            }
            return PaymentStatus.PROCESSING;
        }
        return mapPaymentStatusOnly(paymentStatus);
    }

    /**
     * Un {@code checkout.session.payment_failed} peut être émis plusieurs fois
     * avant qu'un paiement réussisse : tant que la session reste {@code open},
     * on conserve le statut {@link PaymentStatus#PROCESSING}.
     *
     * Si le webhook {@code checkout.session.expired} n'est jamais reçu,
     * le job de réconciliation ({@link com.paylance.service.ReconciliationService})
     * appellera {@link #checkStatus(ProviderConnection, Payment)} et détectera l'expiration via
     * {@code checkout_status=expired}.
     */
    private PaymentStatus mapWebhookStatus(String eventType, String checkoutStatus, String paymentStatus) {
        if ("checkout.session.payment_failed".equals(eventType)
                && (checkoutStatus == null || "open".equalsIgnoreCase(checkoutStatus))) {
            return PaymentStatus.PROCESSING;
        }
        return mapCheckoutSessionStatus(checkoutStatus, paymentStatus);
    }

    private PaymentStatus mapPaymentStatusOnly(String paymentStatus) {
        if (paymentStatus == null) {
            return PaymentStatus.PROCESSING;
        }
        return switch (paymentStatus.toLowerCase()) {
            case "succeeded" -> PaymentStatus.SUCCESS;
            case "cancelled", "failed" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PROCESSING;
        };
    }

    private String extractFailureReason(String paymentStatus, com.fasterxml.jackson.databind.JsonNode lastPaymentError) {
        if (lastPaymentError != null && lastPaymentError.isObject()) {
            if (lastPaymentError.hasNonNull("message")) {
                return lastPaymentError.get("message").asText();
            }
            if (lastPaymentError.hasNonNull("code")) {
                return lastPaymentError.get("code").asText();
            }
        }
        if (paymentStatus != null && ("cancelled".equalsIgnoreCase(paymentStatus) || "failed".equalsIgnoreCase(paymentStatus))) {
            return "Paiement annulé/refusé côté Wave";
        }
        return null;
    }

    /** Wave attend un montant en unité principale (pas en centimes) sous forme de chaîne. */
    private String toWaveAmount(Long amountInCents) {
        return BigDecimal.valueOf(amountInCents, 2).stripTrailingZeros().toPlainString();
    }

    private String writeRaw(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return String.valueOf(response);
        }
    }
}
