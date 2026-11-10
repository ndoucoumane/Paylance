package com.kebpay.service;

import com.kebpay.domain.entity.Payment;
import com.kebpay.domain.entity.WebhookEvent;
import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.Platform;
import com.kebpay.domain.enums.TriggeredBy;
import com.kebpay.domain.enums.WebhookStatus;
import com.kebpay.provider.PaymentProviderClient;
import com.kebpay.provider.PaymentProviderRegistry;
import com.kebpay.provider.WebhookParseResult;
import com.kebpay.repository.PaymentRepository;
import com.kebpay.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookEventRepository webhookEventRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentProviderRegistry providerRegistry;
    @Mock
    private PaymentStatusTransitionService statusTransitionService;
    @Mock
    private PaymentLogService paymentLogService;
    @Mock
    private PaymentNumberGenerator paymentNumberGenerator;
    @Mock
    private PaymentProviderClient waveClient;

    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(
                webhookEventRepository, paymentRepository, providerRegistry,
                statusTransitionService, paymentLogService, paymentNumberGenerator);
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(waveClient);
        when(waveClient.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void handle_unknownTransactionWithoutUnattributedData_marksFailedAndCreatesNoPayment() {
        WebhookParseResult parsed = new WebhookParseResult("evt-1", "unknown-tx", PaymentStatus.SUCCESS, null, null);
        when(waveClient.parseWebhookEvent("{}")).thenReturn(parsed);
        when(paymentRepository.findByProviderTransactionId("unknown-tx")).thenReturn(Optional.empty());

        webhookService.handle(PaymentProvider.WAVE, "{}", "sig");

        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(statusTransitionService);

        ArgumentCaptor<WebhookEvent> eventCaptor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.FAILED);
    }

    @Test
    void handle_merchantPaymentReceivedWithoutMatchingPayment_createsManualPayment() {
        WebhookParseResult.UnattributedPayment unattributed =
                new WebhookParseResult.UnattributedPayment(100_000L, "XOF", "+221761110001");
        WebhookParseResult parsed = new WebhookParseResult("evt-2", "txn-manual-1", PaymentStatus.SUCCESS, null, unattributed);
        when(waveClient.parseWebhookEvent("{}")).thenReturn(parsed);
        when(paymentRepository.findByProviderTransactionId("txn-manual-1")).thenReturn(Optional.empty());
        when(paymentNumberGenerator.generate()).thenReturn("KP-260722-000001-1");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-manual-1");
            }
            return p;
        });
        when(statusTransitionService.transitionStatus(any(Payment.class), eq(PaymentStatus.SUCCESS), anyString(), eq(TriggeredBy.WEBHOOK)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        webhookService.handle(PaymentProvider.WAVE, "{}", "sig");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment created = paymentCaptor.getValue();
        assertThat(created.getPlatform()).isEqualTo(Platform.MANUAL);
        assertThat(created.getProvider()).isEqualTo(PaymentProvider.WAVE);
        assertThat(created.getProviderTransactionId()).isEqualTo("txn-manual-1");
        assertThat(created.getAmount()).isEqualTo(100_000L);
        assertThat(created.getCurrency()).isEqualTo("XOF");
        assertThat(created.getPayerPhone()).isEqualTo("+221761110001");
        assertThat(created.getPaymentNumber()).isEqualTo("KP-260722-000001-1");

        verify(statusTransitionService).transitionStatus(eq(created), eq(PaymentStatus.SUCCESS), anyString(), eq(TriggeredBy.WEBHOOK));

        ArgumentCaptor<WebhookEvent> eventCaptor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.PROCESSED);
    }
}
