package com.paylance.provider.orangemoney;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.paylance.provider.WebhookParseResult;
import com.paylance.web.exception.ProviderCommunicationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

record OrangeMoneyRefundRequest(
        String reference,
        String reason,
        String amount,
        String currency
) {
}

record OrangeMoneyPayoutRequest(
        String amount,
        String currency,
        String customerMsisdn,
        String partnerReference
) {
}

/**
 * Intégration Orange Money via l'API Sonatel (developer.orange-sonatel.com) : OAuth2
 * client_credentials + création de QR code de paiement (eWallet v4) + recherche de
 * transaction par référence (eWallet v1, faute d'endpoint de statut direct par notre ID).
 *
 * <p>Particularité importante : {@code POST /api/eWallet/v4/qrcode} ne renvoie aucun
 * identifiant de transaction Orange à la création (seulement {@code deepLink}/{@code qrcode}).
 * On utilise donc notre propre {@code payment.getId()} comme {@code reference} envoyée à Orange,
 * réutilisé comme {@code providerTransactionId} local — retrouvé tel quel dans le webhook
 * (champ {@code reference}) et dans la recherche de transaction, sans jamais dépendre du vrai
 * {@code transactionId} Orange pour la corrélation.
 */
@Slf4j
@Component
public class OrangeMoneyClient implements PaymentProviderClient {

    private final WebClient webClient;
    private final OrangeMoneyProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public OrangeMoneyClient(WebClient.Builder webClientBuilder, OrangeMoneyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.ORANGE_MONEY;
    }

    @Override
    public ProviderInitiateResult initiate(Payment payment) {
        long start = System.currentTimeMillis();
        log.info("[ORANGE_MONEY_INITIATE_START] Initiation QR code Orange Money pour paymentId={}, montant={} {}",
                payment.getId(), payment.getAmount(), payment.getCurrency());

        long tokenStart = System.currentTimeMillis();
        String accessToken = getAccessToken();
        long tokenDuration = System.currentTimeMillis() - tokenStart;

        OrangeMoneyQrCodeRequest request = new OrangeMoneyQrCodeRequest(
                new OrangeMoneyQrCodeRequest.Money(payment.getCurrency(), toPlainAmount(payment.getAmount())),
                properties.cancelRedirectUrl(),
                properties.successRedirectUrl(),
                properties.merchantCode(),
                Map.of("externalReference", payment.getExternalReference() != null ? payment.getExternalReference() : ""),
                properties.merchantName(),
                payment.getId(),
                String.valueOf(properties.qrCodeValiditySeconds())
        );

        long httpStart = System.currentTimeMillis();
        try {
            OrangeMoneyQrCodeResponse response = webClient.post()
                    .uri("/api/eWallet/v4/qrcode")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(OrangeMoneyQrCodeResponse.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long httpDuration = System.currentTimeMillis() - httpStart;
            long totalDuration = System.currentTimeMillis() - start;

            if (response == null) {
                log.error("[ORANGE_MONEY_INITIATE_EMPTY] Réponse vide d'Orange Money après {}ms pour paymentId={}", totalDuration, payment.getId());
                throw new ProviderCommunicationException("Réponse vide d'Orange Money lors de l'initiation");
            }
            String checkoutReference = response.deepLink() != null ? response.deepLink() : response.qrcode();
            log.info("[ORANGE_MONEY_INITIATE_SUCCESS] QR code Orange Money généré avec succès en {}ms (token: {}ms, http: {}ms) pour paymentId={} (reference={})",
                    totalDuration, tokenDuration, httpDuration, payment.getId(), payment.getId());
            return new ProviderInitiateResult(payment.getId(), checkoutReference, PaymentStatus.PROCESSING, writeRaw(response));
        } catch (WebClientResponseException e) {
            long totalDuration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_INITIATE_ERROR] Erreur Orange Money initiate après {}ms (HTTP {}): {}",
                    totalDuration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de l'initiation du paiement Orange Money : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_INITIATE_FAILED] Exception lors de l'appel Orange Money initiate après {}ms: {}",
                    totalDuration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderStatusResult checkStatus(Payment payment) {
        if (payment.getProviderTransactionId() == null || payment.getProviderTransactionId().isBlank()) {
            if (payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(LocalDateTime.now())) {
                return new ProviderStatusResult(PaymentStatus.EXPIRED, null, "Paiement expiré sans transaction Orange Money", null);
            }
            return new ProviderStatusResult(PaymentStatus.FAILED, null, "Aucun identifiant de transaction Orange Money", null);
        }
        long start = System.currentTimeMillis();
        log.info("[ORANGE_MONEY_CHECK_STATUS_START] Vérification statut Orange Money pour paymentId={}, providerTransactionId={}",
                payment.getId(), payment.getProviderTransactionId());

        String accessToken = getAccessToken();
        try {
            List<OrangeMoneyTransaction> transactions = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/eWallet/v1/transactions")
                            .queryParam("reference", payment.getProviderTransactionId())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<OrangeMoneyTransaction>>() { })
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (transactions == null || transactions.isEmpty()) {
                log.info("[ORANGE_MONEY_CHECK_STATUS_EMPTY] Aucune transaction trouvée pour reference={} ({}ms)",
                        payment.getProviderTransactionId(), duration);
                return new ProviderStatusResult(PaymentStatus.PROCESSING, null, null, null);
            }
            OrangeMoneyTransaction transaction = transactions.get(0);
            PaymentStatus status = mapStatus(transaction.status());
            String failureReason = status == PaymentStatus.FAILED ? transaction.statusReason() : null;
            log.info("[ORANGE_MONEY_CHECK_STATUS_SUCCESS] Statut OM récupéré en {}ms pour reference={} (status={}, omStatus={}, subscriberMsisdn={})",
                    duration, payment.getProviderTransactionId(), status, transaction.status(), transaction.subscriberMsisdn());
            return new ProviderStatusResult(status, writeRaw(transaction), failureReason, transaction.subscriberMsisdn());
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_CHECK_STATUS_ERROR] Erreur Orange Money checkStatus après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de la vérification du statut Orange Money : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_CHECK_STATUS_FAILED] Exception Orange Money checkStatus après {}ms: {}",
                    duration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderRefundResult refund(Payment payment, Long amount, String reason) {
        long start = System.currentTimeMillis();
        log.info("[ORANGE_MONEY_REFUND_START] Demande de remboursement OM pour paymentId={}, amount={}, reason={}",
                payment.getId(), amount, reason);

        String accessToken = getAccessToken();
        String refundPath = properties.refundEndpointPath();
        if (refundPath == null || refundPath.isBlank()) {
            log.error("[ORANGE_MONEY_REFUND_NO_ENDPOINT] Endpoint de remboursement Orange Money non configuré");
            return new ProviderRefundResult(null, RefundStatus.FAILED, null,
                    "Endpoint de remboursement Orange Money non configuré");
        }

        // Le providerTransactionId peut encore contenir l'UUID Paylance (payment.getId())
        // si le webhook n'a pas mis à jour le vrai transactionId Orange. Dans ce cas, on
        // interroge l'API de recherche de transactions pour le résoudre. L'endpoint de
        // refund Orange Money exige le vrai transactionId côté Orange, pas notre reference.
        String orangeTransactionId = resolveOrangeTransactionId(payment, accessToken);

        OrangeMoneyRefundRequest request = new OrangeMoneyRefundRequest(
                orangeTransactionId,
                reason != null ? reason : "refund",
                toPlainAmount(amount != null ? amount : payment.getAmount()),
                payment.getCurrency()
        );

        try {
            var response = webClient.post()
                    .uri(refundPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long duration = System.currentTimeMillis() - start;
            String providerRefundId = null;
            String rawResponse = response;
            try {
                JsonNode root = objectMapper.readTree(response);
                if (root.hasNonNull("transactionId")) {
                    providerRefundId = root.get("transactionId").asText();
                }
            } catch (Exception ignored) {
                // Le provider peut retourner une réponse non-JSON ; on conserve le texte brut.
            }

            log.info("[ORANGE_MONEY_REFUND_SUCCESS] Remboursement OM exécuté avec succès en {}ms pour paymentId={} (providerRefundId={})",
                    duration, payment.getId(), providerRefundId);
            return new ProviderRefundResult(providerRefundId, RefundStatus.SUCCESS, rawResponse, null);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            String responseBody = e.getResponseBodyAsString();
            log.error("[ORANGE_MONEY_REFUND_ERROR] Erreur Orange Money refund après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), responseBody);
            return new ProviderRefundResult(null, RefundStatus.FAILED, responseBody,
                    responseBody != null && !responseBody.isBlank() ? responseBody : e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_REFUND_FAILED] Exception Orange Money refund après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ProviderPayoutResult payout(Payment payment) {
        long start = System.currentTimeMillis();
        log.info("[ORANGE_MONEY_PAYOUT_START] Initiation versement direct (Payout) OM pour paymentId={}, montant={} {}, destinataire={}",
                payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getPayerPhone());

        String accessToken = getAccessToken();
        String payoutPath = properties.payoutEndpointPath() != null && !properties.payoutEndpointPath().isBlank()
                ? properties.payoutEndpointPath()
                : "/api/eWallet/v1/cashins";

        OrangeMoneyPayoutRequest request = new OrangeMoneyPayoutRequest(
                toPlainAmount(payment.getAmount()),
                payment.getCurrency(),
                payment.getPayerPhone(),
                payment.getId()
        );

        try {
            var response = webClient.post()
                    .uri(payoutPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long duration = System.currentTimeMillis() - start;
            String providerTransactionId = payment.getId();
            PaymentStatus status = PaymentStatus.SUCCESS;
            String rawResponse = response;
            String failureReason = null;

            try {
                JsonNode root = objectMapper.readTree(response);
                if (root.hasNonNull("transactionId")) {
                    providerTransactionId = root.get("transactionId").asText();
                }
                if (root.hasNonNull("status")) {
                    status = mapStatus(root.get("status").asText());
                }
                if (status == PaymentStatus.FAILED && root.hasNonNull("message")) {
                    failureReason = root.get("message").asText();
                }
            } catch (Exception ignored) {
            }

            log.info("[ORANGE_MONEY_PAYOUT_SUCCESS] Payout OM exécuté en {}ms pour paymentId={} (providerTxId={}, status={})",
                    duration, payment.getId(), providerTransactionId, status);
            return new ProviderPayoutResult(providerTransactionId, status, rawResponse, failureReason);
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            String responseBody = e.getResponseBodyAsString();
            log.error("[ORANGE_MONEY_PAYOUT_ERROR] Erreur Orange Money payout après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), responseBody);
            return new ProviderPayoutResult(null, PaymentStatus.FAILED, responseBody,
                    responseBody != null && !responseBody.isBlank() ? responseBody : e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_PAYOUT_FAILED] Exception Orange Money payout après {}ms: {}", duration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Résout le vrai {@code transactionId} Orange Money à partir de la {@code reference} stockée
     * localement. Pour les paiements dont le webhook a déjà mis à jour le {@code providerTransactionId},
     * la valeur est retournée telle quelle. Sinon (cas des paiements existants pré-fix), on interroge
     * {@code GET /api/eWallet/v1/transactions?reference=...} pour retrouver la transaction Orange
     * associée à notre {@code payment.getId()} (envoyé comme {@code reference} à la création du QR code).
     */
    private String resolveOrangeTransactionId(Payment payment, String accessToken) {
        String currentId = payment.getProviderTransactionId();

        // Si le providerTransactionId ne ressemble pas à un UUID Paylance (format UUID v4),
        // c'est déjà le vrai transactionId Orange — pas besoin de résoudre.
        if (currentId != null && !currentId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return currentId;
        }

        // Le providerTransactionId est encore notre UUID Paylance. On le recherche comme
        // "reference" dans l'API Orange pour obtenir le vrai transactionId Orange.
        long start = System.currentTimeMillis();
        log.info("[ORANGE_MONEY_RESOLVE_TXID_START] providerTransactionId '{}' ressemble à un UUID Paylance — recherche du vrai transactionId Orange via /api/eWallet/v1/transactions", currentId);
        try {
            List<OrangeMoneyTransaction> transactions = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/eWallet/v1/transactions")
                            .queryParam("reference", currentId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<OrangeMoneyTransaction>>() { })
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long duration = System.currentTimeMillis() - start;
            if (transactions != null && !transactions.isEmpty()) {
                String resolved = transactions.get(0).transactionId();
                if (resolved != null && !resolved.isBlank()) {
                    log.info("[ORANGE_MONEY_RESOLVE_TXID_SUCCESS] transactionId Orange résolu en {}ms pour le paiement {} : {} → {}",
                            duration, payment.getId(), currentId, resolved);
                    return resolved;
                }
            }
            log.warn("[ORANGE_MONEY_RESOLVE_TXID_EMPTY] Aucune transaction Orange trouvée en {}ms pour reference={}", duration, currentId);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[ORANGE_MONEY_RESOLVE_TXID_FAILED] Impossible de résoudre le transactionId Orange pour le paiement {} après {}ms: {}",
                    payment.getId(), duration, e.getMessage());
        }

        // Fallback : retourne l'ID actuel tel quel (le refund échouera probablement, mais
        // l'erreur sera tracée normalement).
        return currentId;
    }

    /**
     * L'authentification du callback Sonatel se fait par un {@code apiKey} choisi par le
     * marchand (pas par Orange), déclaré une fois via {@code POST /api/notification/v1/merchantcallback}
     * et renvoyé tel quel dans {@code Authorization: Basic <apiKey>} sur chaque notification.
     */
    @Override
    public boolean verifyWebhookSignature(String rawPayload, String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank() || properties.callbackApiKey() == null) {
            log.warn("[ORANGE_MONEY_SIGNATURE_EMPTY] Header Authorization manquant ou apiKey non configuré");
            return false;
        }
        String expected = "Basic " + properties.callbackApiKey();
        boolean valid = HmacUtil.constantTimeEquals(authorizationHeader.trim(), expected);
        log.info("[ORANGE_MONEY_SIGNATURE_CHECK] Vérification signature Orange Money: {}", valid ? "VALIDE" : "INVALIDE");
        return valid;
    }

    @Override
    public WebhookParseResult parseWebhookEvent(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String reference = root.hasNonNull("reference") ? root.get("reference").asText() : null;
            String transactionId = root.hasNonNull("transactionId") ? root.get("transactionId").asText() : reference;
            String omStatus = root.hasNonNull("status") ? root.get("status").asText() : null;
            PaymentStatus status = mapStatus(omStatus);
            String failureReason = status == PaymentStatus.FAILED && root.hasNonNull("detail") && !root.get("detail").isNull()
                    ? root.get("detail").asText() : null;
            log.info("[ORANGE_MONEY_WEBHOOK_PARSED] Webhook OM: reference={}, transactionId={}, omStatus={}, finalStatus={}",
                    reference, transactionId, omStatus, status);
            // "reference" (= notre payment.getId(), voir #initiate) sert de clé de corrélation
            // locale à la place du transactionId Orange, inconnu de nous à la création.
            return new WebhookParseResult(transactionId, reference, status, failureReason, null);
        } catch (Exception e) {
            log.error("[ORANGE_MONEY_WEBHOOK_PARSE_ERROR] Impossible de parser le webhook Orange Money: {}", e.getMessage());
            throw new ProviderCommunicationException("Impossible de parser le webhook Orange Money", e);
        }
    }

    private PaymentStatus mapStatus(String omStatus) {
        if (omStatus == null) {
            return PaymentStatus.PROCESSING;
        }
        return switch (omStatus.toUpperCase()) {
            case "SUCCESS" -> PaymentStatus.SUCCESS;
            case "FAILED", "REJECTED", "CANCELLED" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PROCESSING; // ACCEPTED, INITIATED, PENDING, PRE_INITIATED
        };
    }

    private synchronized String getAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            log.debug("[ORANGE_MONEY_TOKEN_CACHE_HIT] Token OAuth en cache valide");
            return cached.token();
        }

        log.info("[ORANGE_MONEY_TOKEN_ACQUISITION_START] Demande de nouveau token OAuth client_credentials...");
        long start = System.currentTimeMillis();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        try {
            OrangeMoneyTokenResponse response = webClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(OrangeMoneyTokenResponse.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            long duration = System.currentTimeMillis() - start;

            if (response == null || response.accessToken() == null) {
                log.error("[ORANGE_MONEY_TOKEN_ACQUISITION_EMPTY] Réponse vide OAuth après {}ms", duration);
                throw new ProviderCommunicationException("Impossible d'obtenir un token OAuth Orange Money");
            }
            long ttl = response.expiresIn() != null ? response.expiresIn() : 3600L;
            CachedToken newToken = new CachedToken(response.accessToken(), Instant.now().plusSeconds(ttl - 30));
            tokenCache.set(newToken);
            log.info("[ORANGE_MONEY_TOKEN_ACQUISITION_SUCCESS] Token OAuth Orange Money obtenu en {}ms (ttl={}s)", duration, ttl);
            return newToken.token();
        } catch (WebClientResponseException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_TOKEN_ACQUISITION_ERROR] Erreur OAuth Orange Money après {}ms (HTTP {}): {}",
                    duration, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ProviderCommunicationException("Échec de l'authentification OAuth Orange Money : " + e.getStatusCode(), e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[ORANGE_MONEY_TOKEN_ACQUISITION_FAILED] Exception OAuth Orange Money après {}ms: {}",
                    duration, e.getMessage(), e);
            throw e;
        }
    }

    /** Orange Money attend un montant en unité principale (pas en centimes) sous forme de chaîne. */
    private String toPlainAmount(Long amountInCents) {
        return BigDecimal.valueOf(amountInCents, 2).stripTrailingZeros().toPlainString();
    }

    private String writeRaw(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return String.valueOf(response);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
