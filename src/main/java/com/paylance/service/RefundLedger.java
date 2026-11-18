package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.RefundRepository;
import com.paylance.tenant.TenantContext;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.exception.BusinessRuleException;
import com.paylance.web.exception.ConflictException;
import com.paylance.web.exception.PaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

/**
 * Écritures sur le solde remboursable d'un paiement. Chaque méthode est une transaction courte
 * qui verrouille d'abord la ligne du paiement ({@link PaymentRepository#findByIdForUpdate}) :
 * les remboursements d'un même paiement sont ainsi strictement sérialisés.
 *
 * <p>Le solde est calculé en déduisant les remboursements {@code SUCCESS} <b>et</b>
 * {@code PENDING} : un remboursement envoyé à l'opérateur mais pas encore confirmé reste
 * réservé. C'est ce qui empêche de rembourser deux fois le même argent.
 *
 * <p>Séparé de {@link RefundService} pour que l'appel réseau à l'opérateur se fasse hors
 * transaction (pas de verrou ni de connexion tenus pendant plusieurs secondes) et pour que les
 * annotations {@code @Transactional} passent bien par le proxy Spring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundLedger {

    static final String WAVE_FULL_REFUND_ONLY = "Wave ne permet que le remboursement total d'un paiement.";
    static final String AMOUNT_EXCEEDS_BALANCE = "Le montant dépasse le solde remboursable.";
    static final String REFUND_ALREADY_PENDING =
            "Un remboursement est déjà en cours pour ce paiement. Attendez sa confirmation avant d'en demander un autre.";
    /** Message public d'un refus opérateur : la réponse brute reste dans les journaux. */
    static final String PROVIDER_REFUSED = "Le prestataire a refusé le remboursement.";

    /** Remboursements qui consomment le solde : confirmés, ou envoyés et pas encore confirmés. */
    public static final EnumSet<RefundStatus> RESERVED = EnumSet.of(RefundStatus.SUCCESS, RefundStatus.PENDING);

    /**
     * Montant remboursable à afficher, avec exactement les règles appliquées par {@link #reserve}.
     *
     * @param reserved somme des remboursements {@link #RESERVED} du paiement
     */
    public static long refundableAmount(Payment payment, long reserved) {
        if (!payment.getStatus().isRefundable()) {
            return 0;
        }
        if (payment.getProvider() == PaymentProvider.WAVE) {
            return reserved == 0 ? payment.getAmount() : 0;
        }
        return Math.max(0, payment.getAmount() - reserved);
    }

    /** Montant réservé pour un remboursement en cours ; le paiement est détaché (lecture seule). */
    public record Reservation(String refundId, Payment payment, long amount) {
    }

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentService paymentService;

    /**
     * Vérifie toutes les règles et réserve le montant en créant un remboursement {@code PENDING}.
     * Rien n'est envoyé à l'opérateur ici.
     *
     * @param requestedAmount {@code null} = solde restant (remboursement total)
     */
    @Transactional
    public Reservation reserve(String paymentId, Long requestedAmount, String reason,
                               String requestedBy, String requestedByUserId) {
        Payment payment = lockPayment(paymentId);
        if (!payment.getStatus().isRefundable()) {
            throw new ConflictException("Impossible de rembourser un paiement au statut "
                    + payment.getStatus().label() + ".");
        }
        if (refundRepository.existsByPaymentIdAndStatus(paymentId, RefundStatus.PENDING)) {
            throw new ConflictException(REFUND_ALREADY_PENDING);
        }

        long remaining = payment.getAmount() - refundRepository.sumAmountByPaymentIdAndStatusIn(paymentId, RESERVED);
        long amount = requestedAmount != null ? requestedAmount : remaining;
        if (amount <= 0 || amount > remaining) {
            throw new BusinessRuleException(AMOUNT_EXCEEDS_BALANCE);
        }
        if (payment.getProvider() == PaymentProvider.WAVE && amount != payment.getAmount()) {
            // Wave rembourse la checkout session entière : un montant partiel, ou un solde déjà
            // entamé, ne correspondrait pas à ce que Wave débiterait réellement.
            throw new BusinessRuleException(WAVE_FULL_REFUND_ONLY);
        }

        Refund refund;
        try {
            refund = refundRepository.saveAndFlush(Refund.builder()
                    .payment(payment)
                    .amount(amount)
                    .reason(reason)
                    .status(RefundStatus.PENDING)
                    .requestedBy(requestedBy)
                    .requestedByUserId(requestedByUserId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // uq_refunds_one_pending_per_payment : filet de sécurité si le verrou était contourné.
            throw new ConflictException(REFUND_ALREADY_PENDING);
        }
        log.info("[REFUND_RESERVED] refundId={} paymentId={} amount={} remainingBefore={}",
                refund.getId(), paymentId, amount, remaining);
        return new Reservation(refund.getId(), payment, amount);
    }

    /** Enregistre la réponse de l'opérateur et met à jour le statut du paiement si l'argent est parti. */
    @Transactional
    public RefundResponse complete(Reservation reservation, ProviderRefundResult result) {
        Payment payment = lockPayment(reservation.payment().getId());
        Refund refund = refundRepository.findById(reservation.refundId())
                .orElseThrow(() -> new IllegalStateException("Remboursement réservé introuvable : " + reservation.refundId()));

        refund.setStatus(result.status());
        refund.setProviderRefundId(result.providerRefundId());
        refund.setProviderRawResponse(result.rawResponse());
        if (result.status() == RefundStatus.FAILED) {
            // failureReason est renvoyé au front : jamais le message brut de l'opérateur
            // (URL interne, corps HTTP...). Le détail est conservé dans payment_logs.
            refund.setFailureReason(PROVIDER_REFUSED);
        }
        refund = refundRepository.save(refund);

        if (result.status() == RefundStatus.SUCCESS) {
            long refunded = refundRepository.sumAmountByPaymentIdAndStatusIn(payment.getId(), EnumSet.of(RefundStatus.SUCCESS));
            PaymentStatus newStatus = refunded >= payment.getAmount() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            statusTransitionService.transitionStatus(payment, newStatus, "Remboursement confirmé par le prestataire", TriggeredBy.API);
        }
        return paymentService.toRefundResponse(refund);
    }

    /**
     * L'opérateur n'a pas répondu de façon exploitable (délai dépassé, coupure réseau) : on ne
     * sait pas si l'argent est parti. Le remboursement reste {@code PENDING}, donc le montant
     * reste réservé et aucun nouveau remboursement n'est possible sur ce paiement. Le supposer
     * échoué permettrait de relancer et de rembourser deux fois. À régulariser par
     * l'exploitation après vérification chez l'opérateur.
     */
    @Transactional(readOnly = true)
    public RefundResponse keepPending(Reservation reservation) {
        Refund refund = refundRepository.findById(reservation.refundId())
                .orElseThrow(() -> new IllegalStateException("Remboursement réservé introuvable : " + reservation.refundId()));
        return paymentService.toRefundResponse(refund);
    }

    private Payment lockPayment(String paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
