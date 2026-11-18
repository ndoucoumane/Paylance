package com.paylance.service;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.repository.RefundRepository;
import com.paylance.support.AbstractApiIT;
import com.paylance.web.dto.RefundRequest;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.exception.BusinessRuleException;
import com.paylance.web.exception.ConflictException;
import com.paylance.web.exception.PaymentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Règles d'argent des remboursements, vérifiées sur une vraie base PostgreSQL. */
class RefundSafetyIT extends AbstractApiIT {

    @Autowired private RefundService refundService;
    @Autowired private RefundRepository refundRepository;

    private Organization org;
    private ProviderAccount omAccount;
    private ProviderAccount waveAccount;

    @BeforeEach
    void setUp() {
        org = createOrganization("Boutique");
        omAccount = createActiveOrangeMoneyAccount(org.getId());
        waveAccount = createActiveWaveAccount(org.getId(), EnvironmentType.LIVE);
    }

    private RefundResponse refund(Payment payment, Long amount) {
        return inTenant(org.getId(), () -> refundService.refund(payment.getId(), new RefundRequest(amount, "Commande annulée"), "Awa Diop", "kc-test"));
    }

    private void providerSucceeds() {
        when(provider.refund(any(), any(), anyLong(), anyString()))
                .thenAnswer(inv -> new ProviderRefundResult("rf-" + System.nanoTime(), RefundStatus.SUCCESS, "{}", null));
    }

    private long refundedSuccessfully(Payment payment) {
        return inTenant(org.getId(), () -> refundRepository.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of(RefundStatus.SUCCESS)));
    }

    private PaymentStatus statusOf(Payment payment) {
        return inTenant(org.getId(), () -> paymentRepository.findById(payment.getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentPartialRefunds_neverExceedPaidAmount() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        // L'opérateur met du temps à répondre : sans réservation sous verrou, les deux
        // demandes liraient le même solde et passeraient toutes les deux.
        when(provider.refund(any(), any(), anyLong(), anyString())).thenAnswer(inv -> {
            Thread.sleep(300);
            return new ProviderRefundResult("rf-" + System.nanoTime(), RefundStatus.SUCCESS, "{}", null);
        });

        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                try {
                    refund(payment, 6_000L); // 6 000 + 6 000 > 10 000 : un seul doit passer
                } catch (ConflictException | BusinessRuleException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(refundedSuccessfully(payment)).isEqualTo(6_000);
        assertThat(rejected.get()).isEqualTo(attempts - 1);
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void concurrentFullWaveRefunds_onlyOnePasses() throws Exception {
        Payment payment = createPayment(org.getId(), null, waveAccount.getId(), PaymentStatus.SUCCESS, 15_000);
        when(provider.refund(any(), any(), anyLong(), anyString())).thenAnswer(inv -> {
            Thread.sleep(300);
            return new ProviderRefundResult("rf", RefundStatus.SUCCESS, "{}", null);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                refund(payment, null);
                return true;
            } catch (ConflictException e) {
                return false;
            }
        };
        Future<Boolean> a = pool.submit(attempt);
        Future<Boolean> b = pool.submit(attempt);
        start.countDown();
        int successes = (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(refundedSuccessfully(payment)).isEqualTo(15_000);
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void wavePartialRefund_isRejectedBeforeCallingProvider() {
        Payment payment = createPayment(org.getId(), null, waveAccount.getId(), PaymentStatus.SUCCESS, 15_000);

        assertThatThrownBy(() -> refund(payment, 5_000L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Wave ne permet que le remboursement total d'un paiement.");
        verify(provider, never()).refund(any(), any(), anyLong(), anyString());
    }

    @Test
    void amountAboveRemainingBalance_isRejected() {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        providerSucceeds();
        refund(payment, 7_000L);

        assertThatThrownBy(() -> refund(payment, 3_001L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Le montant dépasse le solde remboursable.");
        RefundResponse rest = refund(payment, null); // solde restant
        assertThat(rest.amount()).isEqualTo(3_000);
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void nonRefundableStatus_isConflict() {
        Payment payment = createPayment(org.getId(), null, waveAccount.getId(), PaymentStatus.PENDING, 15_000);

        assertThatThrownBy(() -> refund(payment, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Impossible de rembourser un paiement au statut en attente.");
    }

    @Test
    void unknownProviderOutcome_keepsAmountReservedAndBlocksNewRefunds() {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        when(provider.refund(any(), any(), anyLong(), anyString())).thenThrow(new RuntimeException("timeout"));

        RefundResponse uncertain = refund(payment, 4_000L);

        assertThat(uncertain.status()).isEqualTo(RefundStatus.PENDING);
        assertThatThrownBy(() -> refund(payment, 1_000L)).isInstanceOf(ConflictException.class);
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void providerRefusal_isStoredWithoutRawProviderMessage() {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        when(provider.refund(any(), any(), anyLong(), anyString())).thenReturn(new ProviderRefundResult(null,
                RefundStatus.FAILED, "{\"error\":\"x\"}", "400 Bad Request from POST https://api.interne/refund"));

        RefundResponse failed = refund(payment, 4_000L);

        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Le prestataire a refusé le remboursement.");
        // L'échec libère la réservation : un nouveau remboursement est possible.
        providerSucceeds();
        assertThat(refund(payment, 10_000L).status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void disabledProviderAccount_cannotRefund() {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        inTenant(org.getId(), () -> {
            ProviderAccount account = providerAccountRepository.findById(omAccount.getId()).orElseThrow();
            account.setStatus(ProviderAccountStatus.DISABLED);
            return providerAccountRepository.save(account);
        });

        assertThatThrownBy(() -> refund(payment, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Ce compte prestataire n'est pas actif.");
        verify(provider, never()).refund(any(), any(), anyLong(), anyString());
    }

    @Test
    void paymentOfAnotherOrganization_isNotFound() {
        Organization other = createOrganization("Autre");
        ProviderAccount otherAccount = createActiveOrangeMoneyAccount(other.getId());
        Payment foreign = createOrangeMoneyPayment(other.getId(), otherAccount.getId(), 10_000);

        assertThatThrownBy(() -> refund(foreign, null))
                .isInstanceOfAny(PaymentNotFoundException.class, com.paylance.tenant.TenantAccessViolationException.class);
        verify(provider, never()).refund(any(), any(), anyLong(), anyString());
    }
}
