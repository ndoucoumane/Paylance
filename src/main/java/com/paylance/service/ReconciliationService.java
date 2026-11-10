package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderStatusResult;
import com.paylance.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job planifié qui reprend les paiements restés PENDING/PROCESSING trop longtemps,
 * interroge directement le provider pour connaître le statut réel, et corrige toute
 * divergence. Filet de sécurité en cas de webhook perdu/non reçu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final List<PaymentStatus> STALE_STATUSES = List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING);

    private final PaymentRepository paymentRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentLogService paymentLogService;

    @Value("${paylance.reconciliation.stale-after-minutes:10}")
    private int staleAfterMinutes;

    @Scheduled(fixedDelayString = "${paylance.reconciliation.interval-ms:300000}")
    public void reconcilePendingPayments() {
        long start = System.currentTimeMillis();
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleAfterMinutes);
        List<Payment> stalePayments = paymentRepository.findByStatusInAndCreatedAtBefore(STALE_STATUSES, threshold);
        if (stalePayments.isEmpty()) {
            return;
        }
        log.info("[RECONCILIATION_JOB_START] {} paiement(s) en attente à vérifier auprès des providers", stalePayments.size());
        stalePayments.forEach(this::reconcileOne);
        long duration = System.currentTimeMillis() - start;
        log.info("[RECONCILIATION_JOB_COMPLETE] Job de réconciliation terminé en {}ms pour {} paiement(s)", duration, stalePayments.size());
    }

    private void reconcileOne(Payment payment) {
        long start = System.currentTimeMillis();
        log.info("[RECONCILIATION_ONE_START] Réconciliation paiement {}: provider={}, currentStatus={}, providerTxId={}",
                payment.getId(), payment.getProvider(), payment.getStatus(), payment.getProviderTransactionId());
        CorrelationContext.set(payment.getCorrelationId());
        try {
            if (payment.getProviderTransactionId() == null || payment.getProviderTransactionId().isBlank()) {
                if (payment.getExpiresAt() != null && payment.getExpiresAt().isBefore(LocalDateTime.now())) {
                    paymentLogService.warn(payment, "RECONCILIATION_UNINITIATED_EXPIRED",
                            "Paiement expiré sans identifiant de transaction provider");
                    statusTransitionService.transitionStatus(payment, PaymentStatus.EXPIRED,
                            "Reconciliation : paiement expiré sans initiation provider", TriggeredBy.RECONCILIATION);
                } else {
                    paymentLogService.warn(payment, "RECONCILIATION_UNINITIATED_FAILED",
                            "Paiement orphelin sans identifiant de transaction provider");
                    statusTransitionService.transitionStatus(payment, PaymentStatus.FAILED,
                            "Reconciliation : paiement sans initiation provider", TriggeredBy.RECONCILIATION);
                }
                return;
            }

            PaymentProviderClient client = providerRegistry.resolve(payment.getProvider());
            long tCheck = System.currentTimeMillis();
            ProviderStatusResult result = client.checkStatus(payment);
            long checkDuration = System.currentTimeMillis() - tCheck;
            log.info("[RECONCILIATION_STATUS_CHECK] Statut provider {} vérifié en {}ms pour paymentId={} (providerStatus={})",
                    payment.getProvider(), checkDuration, payment.getId(), result.status());
            // Appliqué sur l'instance en mémoire seulement (pas de save() ici) : un save séparé
            // laisserait le @Version local périmé pour le save() suivant de transitionStatus,
            // et provoquerait un ObjectOptimisticLockingFailureException.
            boolean actualPayerPhoneChanged = applyActualPayerPhone(payment, result);

            if (result.status() != payment.getStatus()) {
                paymentLogService.warn(payment, "RECONCILIATION_MISMATCH",
                        "Divergence détectée : Paylance=" + payment.getStatus() + " / Provider=" + result.status());
                statusTransitionService.transitionStatus(payment, result.status(),
                        "Reconciliation : statut corrigé suite à divergence provider", TriggeredBy.RECONCILIATION);
            } else if ((payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING)
                    && payment.getExpiresAt() != null
                    && payment.getExpiresAt().isBefore(LocalDateTime.now())) {
                statusTransitionService.transitionStatus(payment, PaymentStatus.EXPIRED,
                        "Reconciliation : paiement expiré sans confirmation provider", TriggeredBy.RECONCILIATION);
            } else {
                paymentLogService.info(payment, "RECONCILIATION_CHECK", "Statut confirmé conforme : " + result.status());
                if (actualPayerPhoneChanged) {
                    paymentRepository.save(payment);
                }
            }
            long duration = System.currentTimeMillis() - start;
            log.info("[RECONCILIATION_ONE_COMPLETE] Réconciliation terminée en {}ms pour paymentId={}", duration, payment.getId());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            // Alerte : log structuré ERROR — point d'extension pour notification Slack/email/PagerDuty.
            log.error("[RECONCILIATION_ONE_FAILED] Échec de la reconciliation après {}ms pour le paiement {} : {}",
                    duration, payment.getId(), e.getMessage(), e);
            paymentLogService.error(payment, "RECONCILIATION_FAILED", e.getMessage(), null, duration);
        } finally {
            CorrelationContext.clear();
        }
    }

    private boolean applyActualPayerPhone(Payment payment, ProviderStatusResult result) {
        String actual = result.actualPayerPhone();
        if (actual != null && !actual.isBlank() && !actual.equals(payment.getActualPayerPhone())) {
            payment.setActualPayerPhone(actual);
            return true;
        }
        return false;
    }
}
