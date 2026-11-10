package com.kebpay.service;

import com.kebpay.domain.entity.Payment;
import com.kebpay.domain.entity.PaymentStatusHistory;
import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.TriggeredBy;
import com.kebpay.repository.PaymentRepository;
import com.kebpay.repository.PaymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Transitionne le statut d'un paiement et journalise systématiquement l'historique.
 * Isolé dans son propre bean (plutôt qu'une méthode de {@link PaymentService}) pour que
 * {@code @Transactional} et {@code @Retryable} s'appliquent réellement même lorsque
 * {@link PaymentService} l'appelle sur lui-même : un appel {@code this.transitionStatus(...)}
 * depuis l'intérieur de la même classe contournerait le proxy Spring et désactiverait
 * silencieusement ces deux annotations (piège classique de l'auto-invocation AOP).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentStatusTransitionService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository statusHistoryRepository;
    private final PaymentLogService paymentLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @return l'entité {@link Payment} à jour (rechargée/fusionnée) — le paiement passé en
     * argument peut être détaché (chargé dans une autre transaction) ; {@code save(...)} renvoie
     * alors une instance fusionnée distincte avec le {@code @Version} incrémenté. Les appelants
     * doivent utiliser la valeur de retour pour toute sauvegarde ultérieure du même paiement,
     * sous peine de {@link ObjectOptimisticLockingFailureException} au prochain save (version
     * locale restée périmée).
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)
    @Transactional
    public Payment transitionStatus(Payment payment, PaymentStatus newStatus, String reason, TriggeredBy triggeredBy) {
        if (payment.getStatus() == newStatus) {
            return payment;
        }
        long start = System.currentTimeMillis();
        PaymentStatus previous = payment.getStatus();
        log.info("[STATUS_TRANSITION_START] Début transition statut paymentId={}: {} -> {} (déclenché par {})",
                payment.getId(), previous, newStatus, triggeredBy);

        payment.setStatus(newStatus);
        if (newStatus == PaymentStatus.SUCCESS && payment.getPaidAt() == null) {
            payment.setPaidAt(LocalDateTime.now());
        }
        if (newStatus == PaymentStatus.FAILED || newStatus == PaymentStatus.EXPIRED) {
            // Ne pas écraser un failureReason déjà positionné par le provider
            // (ex: "Insufficient funds in wallet." renvoyé par Wave)
            if (payment.getFailureReason() == null || payment.getFailureReason().isBlank()) {
                payment.setFailureReason(reason);
            }
        }

        long dbSaveStart = System.currentTimeMillis();
        payment = paymentRepository.save(payment);
        long dbSaveDuration = System.currentTimeMillis() - dbSaveStart;

        long historyStart = System.currentTimeMillis();
        PaymentStatusHistory history = PaymentStatusHistory.builder()
                .payment(payment)
                .fromStatus(previous)
                .toStatus(newStatus)
                .reason(reason)
                .triggeredBy(triggeredBy)
                .build();
        statusHistoryRepository.save(history);
        long historyDuration = System.currentTimeMillis() - historyStart;

        long logStart = System.currentTimeMillis();
        paymentLogService.info(payment, "STATUS_TRANSITION", previous + " -> " + newStatus + " (" + triggeredBy + ")", reason);
        long logDuration = System.currentTimeMillis() - logStart;

        if (newStatus == PaymentStatus.SUCCESS || newStatus == PaymentStatus.FAILED) {
            // publié après commit (voir CallbackService) pour garantir que le paiement est
            // bien visible en base avant que la notification async n'aille le relire.
            eventPublisher.publishEvent(new PaymentStatusChangedEvent(payment.getId(), newStatus));
        }

        long totalDuration = System.currentTimeMillis() - start;
        log.info("[STATUS_TRANSITION_COMPLETE] Transition réussie en {}ms (paySave: {}ms, historySave: {}ms, logService: {}ms) paymentId={}: {} -> {}",
                totalDuration, dbSaveDuration, historyDuration, logDuration, payment.getId(), previous, newStatus);

        return payment;
    }
}
