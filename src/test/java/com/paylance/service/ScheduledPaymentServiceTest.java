package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.ScheduledPaymentRepository;
import com.paylance.web.dto.PaymentInitiateResponse;
import com.paylance.web.dto.ScheduledPaymentCreateRequest;
import com.paylance.web.dto.ScheduledPaymentResponse;
import com.paylance.web.exception.ScheduledPaymentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledPaymentServiceTest {

    @Mock private ScheduledPaymentRepository scheduledPaymentRepository;
    @Mock private PaymentService paymentService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ProviderAccountService providerAccountService;
    @Mock private DueWorkClaimer dueWorkClaimer;

    private ScheduledPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledPaymentService(scheduledPaymentRepository, paymentService, paymentRepository,
                providerAccountService, dueWorkClaimer);
    }

    private static ScheduledPayment scheduled(ScheduledPaymentStatus status) {
        return ScheduledPayment.builder().id("sched-1").organizationId("org-1").provider(PaymentProvider.WAVE)
                .providerAccountId("pa-1").amount(100000L).payerPhone("+221771234567")
                .scheduledAt(LocalDateTime.now().minusMinutes(5)).status(status).build();
    }

    @Test
    void create_bindsTheSelectedProviderAccount() {
        when(providerAccountService.selectForNewPayment(PaymentProvider.WAVE, null, null)).thenReturn(ProviderAccount.builder()
                .id("pa-1").provider(PaymentProvider.WAVE).environment(EnvironmentType.LIVE).build());
        when(scheduledPaymentRepository.save(any(ScheduledPayment.class))).thenAnswer(i -> i.getArgument(0));

        ScheduledPaymentResponse response = service.create(new ScheduledPaymentCreateRequest(PaymentProvider.WAVE, null,
                50000L, "+221771234567", LocalDateTime.now().plusHours(2), "ref", "note"));

        assertThat(response.providerAccountId()).isEqualTo("pa-1");
        assertThat(response.status()).isEqualTo(ScheduledPaymentStatus.SCHEDULED);
    }

    @Test
    void getById_notFound() {
        when(scheduledPaymentRepository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById("x")).isInstanceOf(ScheduledPaymentNotFoundException.class);
    }

    @Test
    void cancel_onlyWhenScheduled() {
        ScheduledPayment sp = scheduled(ScheduledPaymentStatus.SCHEDULED);
        when(scheduledPaymentRepository.findById("sched-1")).thenReturn(Optional.of(sp));
        when(scheduledPaymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.cancel("sched-1").status()).isEqualTo(ScheduledPaymentStatus.CANCELLED);

        sp.setStatus(ScheduledPaymentStatus.EXECUTED);
        assertThatThrownBy(() -> service.cancel("sched-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void executeDuePayments_executesClaimedItemsInTheirTenant() {
        ScheduledPayment sp = scheduled(ScheduledPaymentStatus.EXECUTING);
        when(dueWorkClaimer.claimScheduledPayments(any(), anyInt()))
                .thenReturn(List.of(new DueWorkClaimer.ClaimedItem("sched-1", "org-1")));
        when(scheduledPaymentRepository.findById("sched-1")).thenReturn(Optional.of(sp));
        when(paymentService.payout(any(), eq("SCHEDULED-sched-1"), any())).thenReturn(new PaymentInitiateResponse(
                "pay-real-1", "PL-1", "corr", PaymentStatus.SUCCESS, PaymentProvider.WAVE, 100000L, "XOF",
                "+221771234567", null, null, Instant.now(), Instant.now()));

        service.executeDuePayments();

        assertThat(sp.getStatus()).isEqualTo(ScheduledPaymentStatus.EXECUTED);
        assertThat(sp.getPaymentId()).isEqualTo("pay-real-1");
        verify(paymentService).payout(argThat(ctx -> "org-1".equals(ctx.organizationId())), eq("SCHEDULED-sched-1"), any());
    }

    @Test
    void search_resolvesPaymentNumbers() {
        ScheduledPayment sp = scheduled(ScheduledPaymentStatus.EXECUTED);
        sp.setPaymentId("pay-1");
        Payment payment = Payment.builder().id("pay-1").paymentNumber("PL-261110-000190-5").build();
        var pageable = PageRequest.of(0, 10);
        when(paymentRepository.findByPaymentNumber("PL-261110-000190-5")).thenReturn(Optional.of(payment));
        when(scheduledPaymentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(new PageImpl<>(List.of(sp), pageable, 1));
        when(paymentRepository.findAllById(any())).thenReturn(List.of(payment));

        var page = service.search("PL-261110-000190-5", null, null, null, null, null, null, null, null, null, pageable);

        assertThat(page.getContent().get(0).paymentNumber()).isEqualTo("PL-261110-000190-5");
    }
}
