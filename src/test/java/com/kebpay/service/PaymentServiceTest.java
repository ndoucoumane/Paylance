package com.kebpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kebpay.domain.entity.Payment;
import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.Platform;
import com.kebpay.idempotency.IdempotencyService;
import com.kebpay.provider.PaymentProviderClient;
import com.kebpay.provider.PaymentProviderRegistry;
import com.kebpay.provider.ProviderInitiateResult;
import com.kebpay.provider.ProviderPayoutResult;
import com.kebpay.repository.PaymentRepository;
import com.kebpay.repository.RefundRepository;
import com.kebpay.web.dto.PaymentInitiateRequest;
import com.kebpay.web.dto.PaymentInitiateResponse;
import com.kebpay.domain.entity.Refund;
import com.kebpay.domain.enums.RefundStatus;
import com.kebpay.web.dto.RefundResponse;
import com.kebpay.web.exception.PaymentNotFoundException;
import com.kebpay.provider.ProviderRefundResult;
import com.kebpay.web.dto.RefundRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private PaymentProviderRegistry providerRegistry;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private PaymentLogService paymentLogService;
    @Mock
    private PaymentStatusTransitionService statusTransitionService;
    @Mock
    private PaymentNumberGenerator paymentNumberGenerator;
    @Mock
    private PaymentProviderClient providerClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, refundRepository, providerRegistry,
                idempotencyService, paymentLogService, statusTransitionService,
                paymentNumberGenerator, new ObjectMapper());
    }

    @Test
    void initiate_createsPaymentAndDelegatesToProvider() {
        PaymentInitiateRequest request = new PaymentInitiateRequest(
                "idem-123", PaymentProvider.WAVE, 150000L, "XOF",
                "+221771234567", "Awa Diop", "order-42", "https://afrisurgelee.test/callback",
                30, null);

        when(paymentRepository.findByPlatformAndIdempotencyKey(Platform.AFRISURGELEE, "idem-123"))
                .thenReturn(Optional.empty());
        when(idempotencyService.acquireLock("AFRISURGELEE", "idem-123")).thenReturn("lock-token");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-1");
            }
            return p;
        });
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(providerClient);
        when(providerClient.initiate(any(Payment.class))).thenReturn(
                new ProviderInitiateResult("wave-tx-1", "https://wave.test/checkout/1", PaymentStatus.PROCESSING, "{}"));
        when(statusTransitionService.transitionStatus(any(Payment.class), eq(PaymentStatus.PROCESSING), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInitiateResponse response = paymentService.initiate(Platform.AFRISURGELEE, request);

        assertThat(response.paymentId()).isEqualTo("pay-1");
        assertThat(response.provider()).isEqualTo(PaymentProvider.WAVE);
        assertThat(response.providerCheckoutReference()).isEqualTo("https://wave.test/checkout/1");

        verify(idempotencyService).release("AFRISURGELEE", "idem-123", "lock-token");
        verify(statusTransitionService).transitionStatus(any(Payment.class), eq(PaymentStatus.PROCESSING), anyString(), any());
    }

    @Test
    void initiate_returnsExistingPayment_whenIdempotencyKeyAlreadyProcessed() {
        Payment existing = Payment.builder()
                .id("pay-existing")
                .correlationId("corr-1")
                .platform(Platform.KSSID)
                .provider(PaymentProvider.ORANGE_MONEY)
                .amount(5000L)
                .currency("XOF")
                .payerPhone("+221770000000")
                .status(PaymentStatus.SUCCESS)
                .build();

        when(paymentRepository.findByPlatformAndIdempotencyKey(Platform.KSSID, "idem-999"))
                .thenReturn(Optional.of(existing));

        PaymentInitiateRequest request = new PaymentInitiateRequest(
                "idem-999", PaymentProvider.ORANGE_MONEY, 5000L, "XOF",
                "+221770000000", null, null, "https://kssid.test/callback", null, null);

        PaymentInitiateResponse response = paymentService.initiate(Platform.KSSID, request);

        assertThat(response.paymentId()).isEqualTo("pay-existing");
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void createPaymentLink_delegatesToInitiate_withGeneratedIdempotencyKeyAndNoCallback() {
        when(paymentRepository.findByPlatformAndIdempotencyKey(eq(Platform.AFRISURGELEE), anyString()))
                .thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(eq("AFRISURGELEE"), anyString())).thenReturn("lock-token");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-link-1");
            }
            return p;
        });
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(providerClient);
        when(providerClient.initiate(any(Payment.class))).thenReturn(
                new ProviderInitiateResult("wave-tx-link-1", "https://pay.wave.com/m/M_xxx", PaymentStatus.PROCESSING, "{}"));
        when(statusTransitionService.transitionStatus(any(Payment.class), eq(PaymentStatus.PROCESSING), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInitiateResponse response = paymentService.createPaymentLink(
                Platform.AFRISURGELEE, PaymentProvider.WAVE, 100_000L, "XOF", "+221771234567", "Awa Diop", null);

        assertThat(response.paymentId()).isEqualTo("pay-link-1");
        assertThat(response.providerCheckoutReference()).isEqualTo("https://pay.wave.com/m/M_xxx");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(paymentCaptor.capture());
        Payment created = paymentCaptor.getAllValues().get(0);
        assertThat(created.getCallbackUrl()).isNull();
        assertThat(created.getExternalReference()).isNull();
        assertThat(created.getIdempotencyKey()).startsWith("admin-link-");
    }

    @Test
    void createPaymentLink_rejectsManualPlatform() {
        assertThatThrownBy(() -> paymentService.createPaymentLink(
                Platform.MANUAL, PaymentProvider.WAVE, 100_000L, "XOF", "+221771234567", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void getRefundsByPaymentId_returnsRefunds_whenPaymentExists() {
        Payment payment = Payment.builder()
                .id("pay-1")
                .paymentNumber("KP-260817-000008-5")
                .build();
        Refund refund = Refund.builder()
                .id("ref-1")
                .payment(payment)
                .amount(5000L)
                .status(RefundStatus.SUCCESS)
                .reason("Client return")
                .providerRefundId("prov-ref-1")
                .requestedBy("API")
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRepository.existsById("pay-1")).thenReturn(true);
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc("pay-1")).thenReturn(List.of(refund));

        List<RefundResponse> results = paymentService.getRefundsByPaymentId("pay-1");

        assertThat(results).hasSize(1);
        RefundResponse resp = results.get(0);
        assertThat(resp.refundId()).isEqualTo("ref-1");
        assertThat(resp.paymentId()).isEqualTo("pay-1");
        assertThat(resp.paymentNumber()).isEqualTo("KP-260817-000008-5");
        assertThat(resp.amount()).isEqualTo(5000L);
        assertThat(resp.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(resp.reason()).isEqualTo("Client return");
        assertThat(resp.providerRefundId()).isEqualTo("prov-ref-1");
    }

    @Test
    void getRefundsByPaymentId_throwsNotFound_whenPaymentDoesNotExist() {
        when(paymentRepository.existsById("unknown-pay")).thenReturn(false);

        assertThatThrownBy(() -> paymentService.getRefundsByPaymentId("unknown-pay"))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void listRefunds_withStatusFilter_delegatesToRepository() {
        Refund refund = Refund.builder()
                .id("ref-1")
                .amount(2000L)
                .status(RefundStatus.SUCCESS)
                .build();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Refund> page = new PageImpl<>(List.of(refund), pageable, 1);

        when(refundRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable))).thenReturn(page);

        Page<RefundResponse> result = paymentService.listRefunds(RefundStatus.SUCCESS, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).refundId()).isEqualTo("ref-1");
    }

    @Test
    void listRefunds_withoutStatus_delegatesToFindAll() {
        Refund refund = Refund.builder()
                .id("ref-2")
                .amount(3000L)
                .status(RefundStatus.PENDING)
                .build();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Refund> page = new PageImpl<>(List.of(refund), pageable, 1);

        when(refundRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable))).thenReturn(page);

        Page<RefundResponse> result = paymentService.listRefunds(null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).refundId()).isEqualTo("ref-2");
    }

    @Test
    void refund_successful_returnsRefundResponseWithPaymentNumber() {
        Payment payment = Payment.builder()
                .id("pay-refund-1")
                .paymentNumber("KP-260820-000186-3")
                .provider(PaymentProvider.WAVE)
                .amount(10000L)
                .status(PaymentStatus.SUCCESS)
                .build();

        when(paymentRepository.findById("pay-refund-1")).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdOrderByCreatedAtDesc("pay-refund-1")).thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> {
            Refund r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId("ref-test-1");
            }
            return r;
        });
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(providerClient);
        when(providerClient.refund(eq(payment), eq(5000L), anyString()))
                .thenReturn(new ProviderRefundResult("prov-ref-123", RefundStatus.SUCCESS, "{}", null));
        when(statusTransitionService.transitionStatus(any(Payment.class), any(PaymentStatus.class), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefundRequest request = new RefundRequest(5000L, "Demande client");
        RefundResponse response = paymentService.refund("pay-refund-1", request);

        assertThat(response.refundId()).isEqualTo("ref-test-1");
        assertThat(response.paymentId()).isEqualTo("pay-refund-1");
        assertThat(response.paymentNumber()).isEqualTo("KP-260820-000186-3");
        assertThat(response.amount()).isEqualTo(5000L);
        assertThat(response.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(response.providerRefundId()).isEqualTo("prov-ref-123");
    }

    @Test
    void payout_success_executesAndTransitionsToSuccess() {
        when(paymentRepository.findByPlatformAndIdempotencyKey(any(), anyString()))
                .thenReturn(Optional.empty());
        when(idempotencyService.acquireLock(anyString(), anyString())).thenReturn("token-payout");
        when(paymentNumberGenerator.generate()).thenReturn("KP-260820-000999-1");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-payout-1");
            }
            return p;
        });
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(providerClient);
        when(providerClient.payout(any(Payment.class)))
                .thenReturn(new ProviderPayoutResult("po-wave-999", PaymentStatus.SUCCESS, "{}", null));
        when(statusTransitionService.transitionStatus(any(Payment.class), eq(PaymentStatus.SUCCESS), anyString(), any()))
                .thenAnswer(invocation -> {
                    Payment p = invocation.getArgument(0);
                    p.setStatus(PaymentStatus.SUCCESS);
                    return p;
                });

        PaymentInitiateRequest request = new PaymentInitiateRequest(
                "SCHEDULED-sched-1", PaymentProvider.WAVE, 90000L, "XOF",
                "+221771234567", "Destinataire", "Facture 101", null, null, null
        );

        PaymentInitiateResponse response = paymentService.payout(Platform.MANUAL, request);

        assertThat(response.paymentId()).isEqualTo("pay-payout-1");
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.amount()).isEqualTo(90000L);
        verify(providerClient).payout(any(Payment.class));
        verify(idempotencyService).release(eq("MANUAL"), eq("SCHEDULED-sched-1"), eq("token-payout"));
    }
}
