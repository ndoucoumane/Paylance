package com.paylance.service;

import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.repository.ScheduledPaymentRepository;
import com.paylance.web.dto.PaymentInitiateRequest;
import com.paylance.web.dto.PaymentInitiateResponse;
import com.paylance.web.dto.ScheduledPaymentCreateRequest;
import com.paylance.web.dto.ScheduledPaymentResponse;
import com.paylance.web.exception.ScheduledPaymentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledPaymentServiceTest {

    @Mock
    private ScheduledPaymentRepository scheduledPaymentRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private com.paylance.repository.PaymentRepository paymentRepository;

    private ScheduledPaymentService scheduledPaymentService;

    @BeforeEach
    void setUp() {
        scheduledPaymentService = new ScheduledPaymentService(scheduledPaymentRepository, paymentService, paymentRepository);
    }

    @Test
    @DisplayName("create() enregistre un paiement programmé avec le statut SCHEDULED et platform MANUAL par défaut")
    void create_success() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(2);
        ScheduledPaymentCreateRequest request = new ScheduledPaymentCreateRequest(
                PaymentProvider.WAVE,
                100000L,
                "+221771234567",
                scheduledAt,
                "EXT-123",
                "Note test"
        );

        ScheduledPayment saved = ScheduledPayment.builder()
                .id("sched-1")
                .provider(PaymentProvider.WAVE)
                .platform(Platform.MANUAL)
                .amount(100000L)
                .payerPhone("+221771234567")
                .scheduledAt(scheduledAt)
                .externalReference("EXT-123")
                .note("Note test")
                .status(ScheduledPaymentStatus.SCHEDULED)
                .createdAt(LocalDateTime.now())
                .build();

        when(scheduledPaymentRepository.save(any(ScheduledPayment.class))).thenReturn(saved);

        ScheduledPaymentResponse response = scheduledPaymentService.create(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("sched-1");
        assertThat(response.status()).isEqualTo(ScheduledPaymentStatus.SCHEDULED);
        assertThat(response.amount()).isEqualTo(100000L);
        assertThat(response.provider()).isEqualTo(PaymentProvider.WAVE);
        assertThat(response.platform()).isEqualTo(Platform.MANUAL);
        verify(scheduledPaymentRepository).save(any(ScheduledPayment.class));
    }

    @Test
    @DisplayName("getById() retourne le paiement programmé s'il existe")
    void getById_found() {
        ScheduledPayment payment = ScheduledPayment.builder()
                .id("sched-1")
                .provider(PaymentProvider.WAVE)
                .platform(Platform.AFRISURGELEE)
                .amount(50000L)
                .payerPhone("+221771234567")
                .scheduledAt(LocalDateTime.now().plusHours(1))
                .status(ScheduledPaymentStatus.SCHEDULED)
                .build();

        when(scheduledPaymentRepository.findById("sched-1")).thenReturn(Optional.of(payment));

        ScheduledPaymentResponse response = scheduledPaymentService.getById("sched-1");

        assertThat(response.id()).isEqualTo("sched-1");
        assertThat(response.amount()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("getById() lève ScheduledPaymentNotFoundException si inexistant")
    void getById_notFound() {
        when(scheduledPaymentRepository.findById("invalid-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scheduledPaymentService.getById("invalid-id"))
                .isInstanceOf(ScheduledPaymentNotFoundException.class);
    }

    @Test
    @DisplayName("cancel() passe le statut à CANCELLED si statut SCHEDULED")
    void cancel_success() {
        ScheduledPayment payment = ScheduledPayment.builder()
                .id("sched-1")
                .status(ScheduledPaymentStatus.SCHEDULED)
                .build();

        when(scheduledPaymentRepository.findById("sched-1")).thenReturn(Optional.of(payment));
        when(scheduledPaymentRepository.save(any(ScheduledPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledPaymentResponse response = scheduledPaymentService.cancel("sched-1");

        assertThat(response.status()).isEqualTo(ScheduledPaymentStatus.CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(ScheduledPaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel() refuse d'annuler si statut non SCHEDULED")
    void cancel_invalidStatus() {
        ScheduledPayment payment = ScheduledPayment.builder()
                .id("sched-1")
                .status(ScheduledPaymentStatus.EXECUTED)
                .build();

        when(scheduledPaymentRepository.findById("sched-1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> scheduledPaymentService.cancel("sched-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Impossible d'annuler");
    }

    @Test
    @DisplayName("executeDuePayments() exécute les paiements échus et met à jour le statut en EXECUTED")
    void executeDuePayments_success() {
        ScheduledPayment duePayment = ScheduledPayment.builder()
                .id("sched-due-1")
                .provider(PaymentProvider.WAVE)
                .platform(Platform.AFRISURGELEE)
                .amount(100000L)
                .payerPhone("+221771234567")
                .scheduledAt(LocalDateTime.now().minusMinutes(5))
                .status(ScheduledPaymentStatus.SCHEDULED)
                .build();

        when(scheduledPaymentRepository.findDueForClaim(any(LocalDateTime.class)))
                .thenReturn(List.of(duePayment));

        PaymentInitiateResponse initiateResponse = new PaymentInitiateResponse(
                "pay-real-1", "PL-260819-001", "corr-1", PaymentStatus.SUCCESS,
                PaymentProvider.WAVE, 100000L, "XOF", "+221771234567",
                "SCHEDULED-sched-due-1", null,
                Instant.now().plusSeconds(1800), Instant.now()
        );

        when(paymentService.payout(eq(Platform.AFRISURGELEE), any(PaymentInitiateRequest.class)))
                .thenReturn(initiateResponse);

        scheduledPaymentService.executeDuePayments();

        assertThat(duePayment.getStatus()).isEqualTo(ScheduledPaymentStatus.EXECUTED);
        assertThat(duePayment.getPaymentId()).isEqualTo("pay-real-1");
        assertThat(duePayment.getExecutedAt()).isNotNull();
        verify(paymentService).payout(eq(Platform.AFRISURGELEE), any(PaymentInitiateRequest.class));
    }

    @Test
    @DisplayName("search() filtre et résout le paymentNumber des paiements programmés")
    void search_filtersAndResolvesPaymentNumber() {
        ScheduledPayment payment = ScheduledPayment.builder()
                .id("sched-1")
                .provider(PaymentProvider.WAVE)
                .platform(Platform.MANUAL)
                .amount(50000L)
                .payerPhone("+221771285612")
                .scheduledAt(LocalDateTime.now())
                .status(ScheduledPaymentStatus.EXECUTED)
                .paymentId("pay-1")
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        Page<ScheduledPayment> page = new PageImpl<>(List.of(payment), pageable, 1);

        com.paylance.domain.entity.Payment realPayment = com.paylance.domain.entity.Payment.builder()
                .id("pay-1")
                .paymentNumber("PL-260820-000190-5")
                .build();

        when(paymentRepository.findByPaymentNumber("PL-260820-000190-5")).thenReturn(Optional.of(realPayment));
        when(scheduledPaymentRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), eq(pageable)))
                .thenReturn(page);
        when(paymentRepository.findAllById(any())).thenReturn(List.of(realPayment));

        Page<ScheduledPaymentResponse> result = scheduledPaymentService.search(
                "PL-260820-000190-5", null, "+221771285612", ScheduledPaymentStatus.EXECUTED,
                PaymentProvider.WAVE, null, null, null, null, null, pageable
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        ScheduledPaymentResponse item = result.getContent().get(0);
        assertThat(item.id()).isEqualTo("sched-1");
        assertThat(item.paymentId()).isEqualTo("pay-1");
        assertThat(item.paymentNumber()).isEqualTo("PL-260820-000190-5");
        assertThat(item.payerPhone()).isEqualTo("+221771285612");
    }
}
