package com.paylance.provider.wave;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.common.HmacUtil;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.ProviderInitiateResult;
import com.paylance.provider.ProviderPayoutResult;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.provider.ProviderStatusResult;
import com.paylance.provider.WebhookParseResult;
import com.paylance.web.exception.ProviderCommunicationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static com.paylance.domain.enums.PaymentProvider.WAVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le {@link WebClient} de {@link WaveClient} est câblé sur un {@link ExchangeFunction} de test :
 * la construction de requête (baseUrl, headers, sérialisation) passe par le vrai code de production,
 * seul le transport réseau est remplacé — pas besoin de serveur HTTP réel.
 */
class WaveClientTest {

    private static final String WEBHOOK_SECRET = "wave-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WaveProperties properties = new WaveProperties(
            "https://api.wave.test", "wave-checkout-api-key", "wave-payout-api-key", "wave-balance-api-key",
            WEBHOOK_SECRET, "https://afrisurgelee.test/success", "https://afrisurgelee.test/error",
            "/v1/payout");

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private WaveClient clientWith(ExchangeFunction exchangeFunction) {
        return new WaveClient(WebClient.builder().exchangeFunction(exchangeFunction), properties, objectMapper);
    }

    private WaveClient clientRespondingWith(HttpStatus status, String responseJson) {
        return clientWith(request -> json(status, responseJson));
    }

    private WaveClient clientThatMustNotBeCalled() {
        return clientWith(request -> {
            throw new AssertionError("Wave API must not be called for this scenario: " + request.url());
        });
    }

    private Payment payment(String providerTransactionId) {
        return Payment.builder()
                .id("pay-1")
                .amount(150000L)
                .currency("XOF")
                .providerTransactionId(providerTransactionId)
                .build();
    }

    @Test
    void getProvider_returnsWave() {
        assertThat(clientThatMustNotBeCalled().getProvider()).isEqualTo(WAVE);
    }

    @Test
    void initiate_mapsOpenSessionToProcessing() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"id":"cos-1","wave_launch_url":"https://checkout.wave.test/cos-1",
                 "checkout_status":"open","payment_status":"processing"}""");

        ProviderInitiateResult result = client.initiate(payment(null));

        assertThat(result.providerTransactionId()).isEqualTo("cos-1");
        assertThat(result.checkoutReference()).isEqualTo("https://checkout.wave.test/cos-1");
        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(result.rawResponse()).contains("cos-1");
    }

    @Test
    void initiate_throwsProviderCommunicationException_onHttpError() {
        WaveClient client = clientRespondingWith(HttpStatus.BAD_REQUEST, """
                {"message":"invalid amount"}""");

        assertThatThrownBy(() -> client.initiate(payment(null)))
                .isInstanceOf(ProviderCommunicationException.class);
    }

    @Test
    void checkStatus_completeAndSucceeded_mapsToSuccess() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"id":"cos-1","checkout_status":"complete","payment_status":"succeeded"}""");

        ProviderStatusResult result = client.checkStatus(payment("cos-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void checkStatus_expiredSession_mapsToExpired_regardlessOfPaymentStatus() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"id":"cos-1","checkout_status":"expired","payment_status":"processing"}""");

        ProviderStatusResult result = client.checkStatus(payment("cos-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void checkStatus_completeAndCancelled_mapsToFailed() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"id":"cos-1","checkout_status":"complete","payment_status":"cancelled"}""");

        ProviderStatusResult result = client.checkStatus(payment("cos-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void checkStatus_nullProviderTransactionId_returnsFailedOrExpiredWithoutHttpCall() {
        WaveClient client = clientThatMustNotBeCalled();

        ProviderStatusResult result = client.checkStatus(payment(null));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).contains("Aucun identifiant");
    }

    @Test
    void checkStatus_404NotFound_mapsToFailed() {
        WaveClient client = clientRespondingWith(HttpStatus.NOT_FOUND, """
                {"message":"Sorry, that URL doesn't look right."}""");

        ProviderStatusResult result = client.checkStatus(payment("cos-unknown"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).contains("Session Wave introuvable");
    }

    @Test
    void refund_partialAmount_isRejectedWithoutCallingApi() {
        WaveClient client = clientThatMustNotBeCalled();

        ProviderRefundResult result = client.refund(payment("cos-1"), 1000L, "partial");

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(result.failureReason()).contains("remboursement total");
    }

    @Test
    void refund_fullAmount_succeeds() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, "{\"status\":\"refunded\"}");

        ProviderRefundResult result = client.refund(payment("cos-1"), 150000L, "client request");

        assertThat(result.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(result.providerRefundId()).isEqualTo("cos-1");
    }

    @Test
    void refund_httpError_returnsFailedResult() {
        WaveClient client = clientRespondingWith(HttpStatus.INTERNAL_SERVER_ERROR, "{\"message\":\"boom\"}");

        ProviderRefundResult result = client.refund(payment("cos-1"), null, "client request");

        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void verifyWebhookSignature_validSignature_returnsTrue() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = "{\"id\":\"evt-1\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacUtil.signHex(timestamp + payload, WEBHOOK_SECRET);

        assertThat(client.verifyWebhookSignature(payload, "t=" + timestamp + ",v1=" + signature)).isTrue();
    }

    @Test
    void verifyWebhookSignature_staleTimestamp_returnsFalse() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = "{\"id\":\"evt-1\"}";
        String staleTimestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        String signature = HmacUtil.signHex(staleTimestamp + payload, WEBHOOK_SECRET);

        assertThat(client.verifyWebhookSignature(payload, "t=" + staleTimestamp + ",v1=" + signature)).isFalse();
    }

    @Test
    void verifyWebhookSignature_wrongSecret_returnsFalse() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = "{\"id\":\"evt-1\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacUtil.signHex(timestamp + payload, "wrong-secret");

        assertThat(client.verifyWebhookSignature(payload, "t=" + timestamp + ",v1=" + signature)).isFalse();
    }

    @Test
    void verifyWebhookSignature_missingOrBlankHeader_returnsFalse() {
        WaveClient client = clientThatMustNotBeCalled();

        assertThat(client.verifyWebhookSignature("{}", null)).isFalse();
        assertThat(client.verifyWebhookSignature("{}", "")).isFalse();
    }

    @Test
    void parseWebhookEvent_checkoutComplete_returnsSuccess() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = """
                {"id":"evt-1","type":"checkout.session.completed",
                 "data":{"id":"cos-1","checkout_status":"complete","payment_status":"succeeded"}}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.providerEventId()).isEqualTo("evt-1");
        assertThat(result.providerTransactionId()).isEqualTo("cos-1");
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void parseWebhookEvent_paymentFailedWhileSessionStillOpen_keepsProcessing() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = """
                {"id":"evt-2","type":"checkout.session.payment_failed",
                 "data":{"id":"cos-1","checkout_status":"open","payment_status":"failed"}}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void parseWebhookEvent_merchantPaymentReceived_returnsUnattributedSuccess() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = """
                {"id":"evt-3","type":"merchant.payment_received",
                 "data":{"id":"txn-manual-1","amount":"1000","fee":"0","currency":"XOF",
                          "sender_mobile":"+221761110001","merchant_name":"KebLink"}}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.providerEventId()).isEqualTo("evt-3");
        assertThat(result.providerTransactionId()).isEqualTo("txn-manual-1");
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.unattributed()).isNotNull();
        assertThat(result.unattributed().amount()).isEqualTo(100_000L);
        assertThat(result.unattributed().currency()).isEqualTo("XOF");
        assertThat(result.unattributed().payerPhone()).isEqualTo("+221761110001");
    }

    @Test
    void parseWebhookEvent_checkoutComplete_hasNoUnattributedPayment() {
        WaveClient client = clientThatMustNotBeCalled();
        String payload = """
                {"id":"evt-1","type":"checkout.session.completed",
                 "data":{"id":"cos-1","checkout_status":"complete","payment_status":"succeeded"}}""";

        WebhookParseResult result = client.parseWebhookEvent(payload);

        assertThat(result.unattributed()).isNull();
    }

    @Test
    void parseWebhookEvent_invalidJson_throwsProviderCommunicationException() {
        WaveClient client = clientThatMustNotBeCalled();

        assertThatThrownBy(() -> client.parseWebhookEvent("not-json"))
                .isInstanceOf(ProviderCommunicationException.class);
    }

    @Test
    void payout_succeeded_returnsSuccess() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"id":"po-123","amount":"900","currency":"XOF","mobile":"+221771234567","status":"succeeded"}""");

        Payment payment = Payment.builder()
                .id("pay-test-1")
                .provider(WAVE)
                .amount(90000L)
                .currency("XOF")
                .payerPhone("+221771234567")
                .build();

        ProviderPayoutResult result = client.payout(payment);

        assertThat(result.providerTransactionId()).isEqualTo("po-123");
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void payout_failed_returnsFailed() {
        WaveClient client = clientRespondingWith(HttpStatus.BAD_REQUEST, """
                {"message":"insufficient funds"}""");

        Payment payment = Payment.builder()
                .id("pay-test-1")
                .provider(WAVE)
                .amount(90000L)
                .currency("XOF")
                .payerPhone("+221771234567")
                .build();

        ProviderPayoutResult result = client.payout(payment);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void getBalance_success_returnsBalance() {
        WaveClient client = clientRespondingWith(HttpStatus.OK, """
                {"amount":"15000","currency":"XOF"}""");

        WaveBalanceResponse response = client.getBalance();

        assertThat(response.amount()).isEqualTo("15000");
        assertThat(response.currency()).isEqualTo("XOF");
    }

    @Test
    void getBalance_error_throwsProviderCommunicationException() {
        WaveClient client = clientRespondingWith(HttpStatus.UNAUTHORIZED, """
                {"message":"invalid api key"}""");

        assertThatThrownBy(client::getBalance)
                .isInstanceOf(ProviderCommunicationException.class);
    }
}
