package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnectionResolver;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.RefundRepository;
import com.paylance.web.dto.RefundRequest;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.exception.PaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Remboursements (total ou partiel) d'un paiement du tenant courant. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderConnectionResolver connectionResolver;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentLogService paymentLogService;
    private final PaymentService paymentService;

    public RefundResponse refund(String paymentId, RefundRequest request, String requestedBy) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() != PaymentStatus.SUCCESS && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Impossible de rembourser un paiement au statut " + payment.getStatus());
        }
        long alreadyRefunded = refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCESS)
                .mapToLong(Refund::getAmount)
                .sum();
        long remaining = payment.getAmount() - alreadyRefunded;
        long refundAmount = request.amount() != null ? request.amount() : remaining;
        if (refundAmount <= 0 || refundAmount > remaining) {
            throw new IllegalArgumentException("Le montant du remboursement dépasse le solde remboursable ("
                    + PaymentService.formatAmount(remaining, payment.getCurrency()) + ")");
        }

        Refund refund = refundRepository.save(Refund.builder()
                .payment(payment)
                .amount(refundAmount)
                .reason(request.reason())
                .status(RefundStatus.PENDING)
                .requestedBy(requestedBy)
                .build());
        paymentLogService.info(payment, "REFUND_REQUESTED", "Remboursement demandé : "
                + PaymentService.formatAmount(refundAmount, payment.getCurrency()), request.reason());

        ProviderRefundResult result = providerRegistry.resolve(payment.getProvider())
                .refund(connectionResolver.forPayment(payment), payment, refundAmount, request.reason());
        refund.setStatus(result.status());
        refund.setProviderRefundId(result.providerRefundId());
        refund.setProviderRawResponse(result.rawResponse());
        refund.setFailureReason(result.failureReason());
        refund = refundRepository.save(refund);

        if (result.status() == RefundStatus.SUCCESS) {
            long totalRefunded = alreadyRefunded + refundAmount;
            PaymentStatus newStatus = totalRefunded >= payment.getAmount() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            statusTransitionService.transitionStatus(payment, newStatus, "Remboursement confirmé par le prestataire", TriggeredBy.API);
        } else {
            paymentLogService.error(payment, "REFUND_FAILED", result.failureReason(), result.rawResponse());
        }
        return paymentService.toRefundResponse(refund);
    }
}
