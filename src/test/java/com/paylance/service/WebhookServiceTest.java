package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.WebhookEvent;
import com.paylance.domain.enums.*;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.WebhookParseResult;
import com.paylance.provider.credentials.WaveCredentials;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.ProviderAccountRepository;
import com.paylance.repository.WebhookEventRepository;
import com.paylance.web.exception.InvalidWebhookSignatureException;
import com.paylance.web.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookServiceTest {

    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ProviderAccountRepository providerAccountRepository;
    @Mock private ProviderAccountService providerAccountService;
    @Mock private PaymentProviderRegistry providerRegistry;
    @Mock private PaymentStatusTransitionService statusTransitionService;
    @Mock private PaymentLogService paymentLogService;
    @Mock private PaymentNumberGenerator paymentNumberGenerator;
    @Mock private CustomerService customerService;
    @Mock private PaymentProviderClient waveClient;

    private WebhookService webhookService;

    private final ProviderAccount account = ProviderAccount.builder().id("pa-1").organizationId("org-1")
            .provider(PaymentProvider.WAVE).environment(EnvironmentType.LIVE).status(ProviderAccountStatus.ACTIVE).build();
    private final ProviderConnection connection = new ProviderConnection(
            "pa-1", PaymentProvider.WAVE, EnvironmentType.LIVE, new WaveCredentials("k", null, null), "secret");

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(webhookEventRepository, paymentRepository, providerAccountRepository,
                providerAccountService, providerRegistry, statusTransitionService, paymentLogService,
                paymentNumberGenerator, customerService);
        when(providerAccountRepository.findById("pa-1")).thenReturn(Optional.of(account));
        when(providerAccountService.toConnection(account)).thenReturn(connection);
        when(providerRegistry.resolve(PaymentProvider.WAVE)).thenReturn(waveClient);
        when(waveClient.verifyWebhookSignature(eq(connection), anyString(), anyString())).thenReturn(true);
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void handle_unknownAccount_isNotFound() {
        assertThatThrownBy(() -> webhookService.handle(PaymentProvider.WAVE, "pa-unknown", "{}", "sig"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void handle_accountOfOtherProvider_isNotFound() {
        assertThatThrownBy(() -> webhookService.handle(PaymentProvider.ORANGE_MONEY, "pa-1", "{}", "sig"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void handle_invalidSignature_isAuditedAndRejected() {
        when(waveClient.verifyWebhookSignature(eq(connection), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> webhookService.handle(PaymentProvider.WAVE, "pa-1", "{}", "bad"))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WebhookStatus.INVALID_SIGNATURE);
        assertThat(captor.getValue().getProviderAccountId()).isEqualTo("pa-1");
    }

    @Test
    void handle_unknownTransaction_marksFailedAndCreatesNoPayment() {
        when(waveClient.parseWebhookEvent("{}")).thenReturn(new WebhookParseResult("evt-1", "unknown-tx", PaymentStatus.SUCCESS, null, null));
        when(paymentRepository.findByProviderAccountIdAndProviderTransactionId("pa-1", "unknown-tx")).thenReturn(Optional.empty());

        webhookService.handle(PaymentProvider.WAVE, "pa-1", "{}", "sig");

        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(statusTransitionService);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WebhookStatus.FAILED);
    }

    @Test
    void handle_merchantPaymentReceived_createsUnattributedPaymentOnThatAccount() {
        var unattributed = new WebhookParseResult.UnattributedPayment(100_000L, "XOF", "+221761110001");
        when(waveClient.parseWebhookEvent("{}")).thenReturn(new WebhookParseResult("evt-2", "txn-manual-1", PaymentStatus.SUCCESS, null, unattributed));
        when(paymentRepository.findByProviderAccountIdAndProviderTransactionId("pa-1", "txn-manual-1")).thenReturn(Optional.empty());
        when(paymentNumberGenerator.generate()).thenReturn("PL-261110-000001-7");
        when(customerService.upsert("+221761110001", null, null, null)).thenReturn("cust-1");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId("pay-manual-1");
            }
            return p;
        });
        when(statusTransitionService.transitionStatus(any(Payment.class), eq(PaymentStatus.SUCCESS), anyString(), eq(TriggeredBy.WEBHOOK)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        webhookService.handle(PaymentProvider.WAVE, "pa-1", "{}", "sig");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment created = captor.getValue();
        assertThat(created.getApplicationId()).isNull();
        assertThat(created.getProviderAccountId()).isEqualTo("pa-1");
        assertThat(created.getCustomerId()).isEqualTo("cust-1");
        assertThat(created.getAmount()).isEqualTo(100_000L);
        verify(statusTransitionService).transitionStatus(any(Payment.class), eq(PaymentStatus.SUCCESS), anyString(), eq(TriggeredBy.WEBHOOK));
    }
}
