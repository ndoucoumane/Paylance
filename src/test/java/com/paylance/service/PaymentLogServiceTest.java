package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.PaymentLog;
import com.paylance.domain.enums.LogLevel;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.repository.PaymentLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentLogServiceTest {

    @Mock
    private PaymentLogRepository paymentLogRepository;

    private PaymentLogService paymentLogService;

    @BeforeEach
    void setUp() {
        paymentLogService = new PaymentLogService(paymentLogRepository);
    }

    @Test
    void info_withDuration_persistsFormattedDurationInMessage() {
        Payment payment = Payment.builder()
                .id("pay-1")
                .correlationId("corr-123")
                .status(PaymentStatus.PENDING)
                .build();

        paymentLogService.info(payment, "PROVIDER_RESPONSE", "Réponse reçue", "{\"status\":\"ok\"}", 250L);

        ArgumentCaptor<PaymentLog> captor = ArgumentCaptor.forClass(PaymentLog.class);
        verify(paymentLogRepository).save(captor.capture());

        PaymentLog saved = captor.getValue();
        assertThat(saved.getPayment()).isEqualTo(payment);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-123");
        assertThat(saved.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(saved.getEvent()).isEqualTo("PROVIDER_RESPONSE");
        assertThat(saved.getMessage()).isEqualTo("Réponse reçue [durée: 250ms]");
        assertThat(saved.getPayload()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void warn_withDuration_persistsFormattedDurationInMessage() {
        Payment payment = Payment.builder()
                .id("pay-2")
                .correlationId("corr-456")
                .status(PaymentStatus.PENDING)
                .build();

        paymentLogService.warn(payment, "RECONCILIATION_MISMATCH", "Divergence détectée", 150L);

        ArgumentCaptor<PaymentLog> captor = ArgumentCaptor.forClass(PaymentLog.class);
        verify(paymentLogRepository).save(captor.capture());

        PaymentLog saved = captor.getValue();
        assertThat(saved.getLevel()).isEqualTo(LogLevel.WARN);
        assertThat(saved.getMessage()).isEqualTo("Divergence détectée [durée: 150ms]");
    }

    @Test
    void error_withDuration_persistsFormattedDurationInMessage() {
        Payment payment = Payment.builder()
                .id("pay-3")
                .correlationId("corr-789")
                .status(PaymentStatus.FAILED)
                .build();

        paymentLogService.error(payment, "PROVIDER_CALL_FAILED", "Timeout réseau", null, 15000L);

        ArgumentCaptor<PaymentLog> captor = ArgumentCaptor.forClass(PaymentLog.class);
        verify(paymentLogRepository).save(captor.capture());

        PaymentLog saved = captor.getValue();
        assertThat(saved.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(saved.getMessage()).isEqualTo("Timeout réseau [durée: 15000ms]");
    }
}
