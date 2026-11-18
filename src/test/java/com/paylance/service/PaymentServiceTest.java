package com.paylance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.domain.entity.Application;
import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.idempotency.IdempotencyService;
import com.paylance.provider.*;
import com.paylance.provider.credentials.WaveCredentials;
import com.paylance.repository.ApplicationRepository;
import com.paylance.repository.OrganizationRepository;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.RefundRepository;
import com.paylance.web.dto.PaymentInitiateRequest;
import com.paylance.web.dto.PaymentInitiateResponse;
import com.paylance.web.dto.PaymentLinkRequest;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.exception.PaymentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String ORG = "org-1";
    private static final String APP = "app-1";

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private PaymentProviderRegistry providerRegistry;
    @Mock private ProviderConnectionResolver connectionResolver;
    @Mock private ProviderAccountService providerAccountService;
    @Mock private CustomerService customerService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private PaymentLogService paymentLogService;
    @Mock private PaymentStatusTransitionService statusTransitionService;
    @Mock private PaymentNumberGenerator paymentNumberGenerator;
    @Mock private PaymentProviderClient providerClient;

    private PaymentService paymentService;

    private final ProviderAccount account = ProviderAccount.builder()
            .id("pa-1").organizationId(ORG).provider(PaymentProvider.WAVE)
            .environment(EnvironmentType.LIVE).status(ProviderAccountStatus.ACTIVE).build();
    private final ProviderConnection connection = new ProviderConnection(
            "pa-1", PaymentProvider.WAVE, EnvironmentType.LIVE, new WaveCredentials("k", null, null), "s");

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, refundRepository, applicationRepository,
                organizationRepository, providerRegistry, connectionResolver, providerAccountService, customerService,
                idempotencyService, paymentLogService, statusTransitionService, paymentNumberGenerator, new ObjectMapper());

        when(applicationRepository.findById(APP)).thenReturn(Optional.of(Application.builder()
                .id(APP).organizationId(ORG).environment(EnvironmentType.LIVE)
                .successUrl("https://merchant.test/ok").errorUrl("https://merchant.test/ko")
                .callbackUrl("https://merchant.test/callback").build()));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(Organization.builder().id(ORG).defaultCurrency("XOF").build()));
        when(providerAccountService.selectForNewPayment(eq(PaymentProvider.WAVE), any(), any())).thenReturn(account);
        when(customerService.upsert(anyString(), any(), any(), any())).thenReturn("cust-1");
        when(paymentNumberGenerator.generate()).thenReturn("PL-261110-000001-7");
        when(idempotencyService.acquireLock(anyString(), anyString())).thenReturn("lock-token");
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(providerClient);
        when(connectionResolver.forPayment(any())).thenReturn(connection);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-1");
            }
            return p;
        });
        when(statusTransitionService.transitionStatus(any(Payment.class), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Payment p = invocation.getArgument(0);
                    p.setStatus(invocation.getArgument(1));
                    return p;
                });
    }

    private static PaymentInitiateRequest request() {
        return new PaymentInitiateRequest(PaymentProvider.WAVE, null, 150000L, null, "+221 77 123 45 67", "Awa Diop",
                null, null, "order-42", null, null, null, 30, null);
    }

    @Test
    void initiate_createsPaymentOnSelectedAccount_withApplicationDefaults() {
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(APP, "idem-123")).thenReturn(Optional.empty());
        when(providerClient.initiate(eq(connection), any(Payment.class))).thenReturn(
                new ProviderInitiateResult("wave-tx-1", "https://wave.test/checkout/1", PaymentStatus.PROCESSING, "{}"));

        PaymentInitiateResponse response = paymentService.initiate(
                PaymentContext.forApiKey(ORG, APP, EnvironmentType.LIVE), "idem-123", request());

        assertThat(response.paymentId()).isEqualTo("pay-1");
        assertThat(response.providerCheckoutReference()).isEqualTo("https://wave.test/checkout/1");
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(captor.capture());
        Payment created = captor.getAllValues().get(0);
        assertThat(created.getApplicationId()).isEqualTo(APP);
        assertThat(created.getProviderAccountId()).isEqualTo("pa-1");
        assertThat(created.getCustomerId()).isEqualTo("cust-1");
        assertThat(created.getEnvironment()).isEqualTo(EnvironmentType.LIVE);
        assertThat(created.getPayerPhone()).isEqualTo("+221771234567");
        assertThat(created.getSuccessUrl()).isEqualTo("https://merchant.test/ok");
        assertThat(created.getCallbackUrl()).isEqualTo("https://merchant.test/callback");
        assertThat(created.getCurrency()).isEqualTo("XOF");
        verify(providerAccountService).selectForNewPayment(PaymentProvider.WAVE, EnvironmentType.LIVE, null);
        verify(idempotencyService).release(ORG + ":" + APP, "idem-123", "lock-token");
    }

    @Test
    void initiate_returnsExistingPayment_whenIdempotencyKeyAlreadyUsed() {
        Payment existing = Payment.builder().id("pay-existing").provider(PaymentProvider.WAVE)
                .amount(5000L).currency("XOF").status(PaymentStatus.SUCCESS).build();
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(APP, "idem-999")).thenReturn(Optional.of(existing));

        PaymentInitiateResponse response = paymentService.initiate(
                PaymentContext.forApiKey(ORG, APP, EnvironmentType.LIVE), "idem-999", request());

        assertThat(response.paymentId()).isEqualTo("pay-existing");
        verifyNoInteractions(providerClient, idempotencyService);
    }

    @Test
    void initiate_requiresIdempotencyKey() {
        assertThatThrownBy(() -> paymentService.initiate(PaymentContext.forApiKey(ORG, APP, EnvironmentType.LIVE), " ", request()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_withoutApplicationAndWithoutRedirectUrls_isRejected() {
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(isNull(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.initiate(PaymentContext.forDashboard(ORG, null, "user-1"), "k", request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successUrl");
        verifyNoInteractions(providerClient);
    }

    @Test
    void initiate_marksPaymentFailed_whenProviderCallFails() {
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(APP, "idem-fail")).thenReturn(Optional.empty());
        when(providerClient.initiate(any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> paymentService.initiate(PaymentContext.forApiKey(ORG, APP, EnvironmentType.LIVE), "idem-fail", request()))
                .hasMessage("boom");
        verify(statusTransitionService).transitionStatus(any(Payment.class), eq(PaymentStatus.FAILED), anyString(), any());
        verify(idempotencyService).release(anyString(), eq("idem-fail"), eq("lock-token"));
    }

    @Test
    void createPaymentLink_generatesIdempotencyKey_andHasNoCallback() {
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(isNull(), anyString())).thenReturn(Optional.empty());
        when(providerClient.initiate(any(), any(Payment.class))).thenReturn(
                new ProviderInitiateResult("wave-tx-2", "https://wave.test/checkout/2", PaymentStatus.PROCESSING, "{}"));

        PaymentLinkRequest link = new PaymentLinkRequest(null, PaymentProvider.WAVE, null, 2500000L, "XOF",
                "+221771234567", "Client", "https://merchant.test/ok", "https://merchant.test/ko", 60);
        PaymentInitiateResponse response = paymentService.createPaymentLink(PaymentContext.forDashboard(ORG, null, "user-1"), link);

        assertThat(response.providerCheckoutReference()).isEqualTo("https://wave.test/checkout/2");
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getIdempotencyKey()).startsWith("link-");
        assertThat(captor.getAllValues().get(0).getApplicationId()).isNull();
        assertThat(captor.getAllValues().get(0).getCallbackUrl()).isNull();
    }

    @Test
    void payout_success_transitionsToSuccess() {
        when(paymentRepository.findByApplicationIdAndIdempotencyKey(isNull(), eq("SCHEDULED-1"))).thenReturn(Optional.empty());
        when(providerClient.payout(eq(connection), any(Payment.class)))
                .thenReturn(new ProviderPayoutResult("po-wave-999", PaymentStatus.SUCCESS, "{}", null));

        PaymentInitiateResponse response = paymentService.payout(PaymentContext.forSystem(ORG, "test"), "SCHEDULED-1",
                new PaymentInitiateRequest(PaymentProvider.WAVE, "pa-1", 90000L, "XOF", "+221771234567", null,
                        null, null, null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(providerClient).payout(eq(connection), any(Payment.class));
    }

    @Test
    void getRefundsByPaymentId_returnsRefunds_whenPaymentExists() {
        Payment payment = Payment.builder().id("pay-1").paymentNumber("PL-261110-000008-5").payerPhone("+221770000000").build();
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc("pay-1")).thenReturn(List.of(
                Refund.builder().id("ref-1").payment(payment).amount(1000L).status(RefundStatus.SUCCESS).build()));

        List<RefundResponse> refunds = paymentService.getRefundsByPaymentId("pay-1");

        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).paymentNumber()).isEqualTo("PL-261110-000008-5");
    }

    @Test
    void getRefundsByPaymentId_throwsNotFound_whenPaymentMissing() {
        when(paymentRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.getRefundsByPaymentId("nope")).isInstanceOf(PaymentNotFoundException.class);
    }
}
