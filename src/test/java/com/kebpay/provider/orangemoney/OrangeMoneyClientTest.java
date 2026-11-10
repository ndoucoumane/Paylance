package com.kebpay.provider.orangemoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kebpay.domain.entity.Payment;
import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.RefundStatus;
import com.kebpay.provider.ProviderInitiateResult;
import com.kebpay.provider.ProviderPayoutResult;
import com.kebpay.provider.ProviderRefundResult;
import com.kebpay.provider.ProviderStatusResult;
import com.kebpay.provider.WebhookParseResult;
import com.kebpay.web.exception.ProviderCommunicationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static com.kebpay.domain.enums.PaymentProvider.ORANGE_MONEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le {@link WebClient} d'{@link OrangeMoneyClient} est câblé sur un {@link ExchangeFunction} de test :
 * la construction de requête (baseUrl, OAuth, sérialisation) passe par le vrai code de production,
 * seul le transport réseau est remplacé — pas besoin de serveur HTTP réel.
 */
class OrangeMoneyClientTest {

    private static final String TOKEN_RESPONSE = """
            {"access_token":"token-abc","expires_in":3600,"token_type":"Bearer"}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrangeMoneyProperties properties = new OrangeMoneyProperties(
            "https://api.orange.test", "client-id", "client-secret",
            "merchant-code", "Afrisurgelée", "om-callback-key",
            "https://afrisurgelee.test/success", "https://afrisurgelee.test/cancel", 300,
            "/api/eWallet/v4/refund", "/api/eWallet/v1/cashins");

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private OrangeMoneyClient clientWith(ExchangeFunction exchangeFunction) {
        return new OrangeMoneyClient(WebClient.builder().exchangeFunction(exchangeFunction), properties, objectMapper);
    }

    /** Répond au endpoint OAuth avec un token valide, puis délègue le reste à {@code businessResponse}. */
    private OrangeMoneyClient clientAuthenticatedThenRespondingWith(HttpStatus status, String businessResponseJson) {
        return clientWith(request -> request.url().getPath().endsWith("/oauth/token")
                ? json(HttpStatus.OK, TOKEN_RESPONSE)
                : json(status, businessResponseJson));
    }

    private OrangeMoneyClient clientThatMustNotBeCalled() {
        return clientWith(request -> {
            throw new AssertionError("Orange Money API must not be called for this scenario: " + request.url());
        });
    }

    private Payment payment(String providerTransactionId) {
        return Payment.builder()
                .id("pay-1")
                .amount(5000L)
                .currency("XOF")
                .providerTransactionId(providerTransactionId)
                .build();
    }

    @Test
    void getProvider_returnsOrangeMoney() {
        assertThat(clientThatMustNotBeCalled().getProvider()).isEqualTo(ORANGE_MONEY);
    }

    @Test
    void initiate_fetchesTokenThenCreatesQrCode() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, """
                {"deepLink":"https://om.test/deeplink/1","qrcode":"data:image/png;base64,xxx"}""");

        ProviderInitiateResult result = client.initiate(payment(null));

        assertThat(result.providerTransactionId()).isEqualTo("pay-1");
        assertThat(result.checkoutReference()).isEqualTo("https://om.test/deeplink/1");
        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void initiate_fallsBackToQrcodeField_whenDeepLinkMissing() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, """
                {"deepLink":null,"qrcode":"data:image/png;base64,xxx"}""");

        ProviderInitiateResult result = client.initiate(payment(null));

        assertThat(result.checkoutReference()).isEqualTo("data:image/png;base64,xxx");
    }

    @Test
    void initiate_cachesAccessToken_acrossCalls() {
        AtomicInteger tokenCalls = new AtomicInteger();
        OrangeMoneyClient client = clientWith(request -> {
            if (request.url().getPath().endsWith("/oauth/token")) {
                tokenCalls.incrementAndGet();
                return json(HttpStatus.OK, TOKEN_RESPONSE);
            }
            return json(HttpStatus.OK, "{\"deepLink\":\"https://om.test/deeplink/1\",\"qrcode\":null}");
        });

        client.initiate(payment(null));
        client.initiate(payment(null));

        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void initiate_throwsProviderCommunicationException_onQrCodeHttpError() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(
                HttpStatus.BAD_REQUEST, "{\"message\":\"invalid merchant\"}");

        assertThatThrownBy(() -> client.initiate(payment(null)))
                .isInstanceOf(ProviderCommunicationException.class);
    }

    @Test
    void checkStatus_returnsSuccess_whenTransactionFound() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, """
                [{"reference":"pay-1","transactionId":"om-tx-1","status":"SUCCESS","statusReason":null}]""");

        ProviderStatusResult result = client.checkStatus(payment("pay-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void checkStatus_returnsProcessing_whenNoTransactionYet() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, "[]");

        ProviderStatusResult result = client.checkStatus(payment("pay-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(result.rawResponse()).isNull();
    }

    @Test
    void checkStatus_returnsFailedWithReason_whenRejected() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, """
                [{"reference":"pay-1","transactionId":"om-tx-1","status":"REJECTED","statusReason":"Solde insuffisant"}]""");

        ProviderStatusResult result = client.checkStatus(payment("pay-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Solde insuffisant");
    }

    @Test
    void checkStatus_nullProviderTransactionId_returnsFailedWithoutCallingApi() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();

        ProviderStatusResult result = client.checkStatus(payment(null));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).contains("Aucun identifiant");
    }

    @Test
    void refund_postsToRefundEndpointAndReturnsSuccess() {
        OrangeMoneyClient client = clientWith(request -> {
            if (request.url().getPath().endsWith("/oauth/token")) {
                return json(HttpStatus.OK, TOKEN_RESPONSE);
            }
            if (request.url().getPath().endsWith("/api/eWallet/v4/refund")) {
                return json(HttpStatus.OK, "{\"transactionId\":\"refund-1\",\"status\":\"SUCCESS\"}");
            }
            return json(HttpStatus.NOT_FOUND, "{}" );
        });

        ProviderRefundResult result = client.refund(payment("pay-1"), 2500L, "client request");

        assertThat(result.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(result.providerRefundId()).isEqualTo("refund-1");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void refund_returnsFailed_whenHttpErrorOccurs() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.BAD_REQUEST, "{\"message\":\"refund denied\"}");

        ProviderRefundResult result = client.refund(payment("pay-1"), 2500L, "client request");

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.failureReason()).contains("refund denied");
    }

    @Test
    void verifyWebhookSignature_validBasicAuth_returnsTrue() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();

        assertThat(client.verifyWebhookSignature("{}", "Basic om-callback-key")).isTrue();
    }

    @Test
    void verifyWebhookSignature_wrongKey_returnsFalse() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();

        assertThat(client.verifyWebhookSignature("{}", "Basic wrong-key")).isFalse();
    }

    @Test
    void verifyWebhookSignature_missingHeader_returnsFalse() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();

        assertThat(client.verifyWebhookSignature("{}", null)).isFalse();
    }

    @Test
    void parseWebhookEvent_success_usesReferenceAsCorrelationKey() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();
        String payload = """
                {"reference":"pay-1","transactionId":"om-tx-1","status":"SUCCESS"}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.providerEventId()).isEqualTo("om-tx-1");
        assertThat(result.providerTransactionId()).isEqualTo("pay-1");
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void parseWebhookEvent_failed_extractsDetailAsFailureReason() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();
        String payload = """
                {"reference":"pay-1","transactionId":"om-tx-1","status":"FAILED","detail":"Timeout usager"}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Timeout usager");
    }

    @Test
    void parseWebhookEvent_invalidJson_throwsProviderCommunicationException() {
        OrangeMoneyClient client = clientThatMustNotBeCalled();

        assertThatThrownBy(() -> client.parseWebhookEvent("not-json"))
                .isInstanceOf(ProviderCommunicationException.class);
    }

    @Test
    void payout_success_returnsSuccess() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.OK, """
                {"transactionId":"om-cashin-123","status":"SUCCESS"}""");

        Payment payment = Payment.builder()
                .id("pay-om-1")
                .provider(ORANGE_MONEY)
                .amount(50000L)
                .currency("XOF")
                .payerPhone("+221771234567")
                .build();

        ProviderPayoutResult result = client.payout(payment);

        assertThat(result.providerTransactionId()).isEqualTo("om-cashin-123");
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void payout_failed_returnsFailed() {
        OrangeMoneyClient client = clientAuthenticatedThenRespondingWith(HttpStatus.BAD_REQUEST, """
                {"message":"Solde insuffisant"}""");

        Payment payment = Payment.builder()
                .id("pay-om-1")
                .provider(ORANGE_MONEY)
                .amount(50000L)
                .currency("XOF")
                .payerPhone("+221771234567")
                .build();

        ProviderPayoutResult result = client.payout(payment);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    }
}
