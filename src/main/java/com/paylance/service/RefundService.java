package com.paylance.service;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.ProviderConnectionResolver;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.web.dto.RefundRequest;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.exception.BusinessRuleException;
import com.paylance.web.exception.PaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Remboursements (total ou partiel) d'un paiement du tenant courant, en trois temps :
 * <ol>
 *   <li>{@link RefundLedger#reserve} : contrôles et réservation du montant sous verrou ;</li>
 *   <li>appel à l'opérateur, hors transaction ;</li>
 *   <li>{@link RefundLedger#complete} : enregistrement du résultat et du nouveau statut.</li>
 * </ol>
 * Deux demandes simultanées sur le même paiement ne peuvent pas dépasser le montant payé : la
 * seconde voit la réservation de la première (voir {@link RefundLedger}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    static final String PROVIDER_ACCOUNT_INACTIVE = "Ce compte prestataire n'est pas actif.";

    private final PaymentRepository paymentRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderConnectionResolver connectionResolver;
    private final ProviderAccountService providerAccountService;
    private final PaymentLogService paymentLogService;
    private final RefundLedger ledger;

    /**
     * @param requestedBy       nom affiché au marchand (« Awa Diop ») ou {@code API:<applicationId>}
     * @param requestedByUserId {@code sub} Keycloak de l'auteur pour l'audit, {@code null} pour une application
     */
    public RefundResponse refund(String paymentId, RefundRequest request, String requestedBy, String requestedByUserId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // Identifiants résolus avant toute réservation : un compte désactivé ou illisible ne
        // laisse pas de remboursement PENDING orphelin.
        ProviderAccount account = providerAccountService.get(payment.getProviderAccountId());
        if (account.getStatus() != ProviderAccountStatus.ACTIVE) {
            throw new BusinessRuleException(PROVIDER_ACCOUNT_INACTIVE);
        }
        ProviderConnection connection = connectionResolver.forPayment(payment);

        RefundLedger.Reservation reservation = ledger.reserve(paymentId, request.amount(), request.reason(),
                requestedBy, requestedByUserId);
        Payment reserved = reservation.payment();
        paymentLogService.info(reserved, "REFUND_REQUESTED", "Remboursement demandé : "
                + PaymentService.formatAmount(reservation.amount(), reserved.getCurrency()), request.reason());

        ProviderRefundResult result;
        try {
            result = providerRegistry.resolve(reserved.getProvider())
                    .refund(connection, reserved, reservation.amount(), request.reason());
        } catch (RuntimeException e) {
            log.error("[REFUND_OUTCOME_UNKNOWN] refundId={} paymentId={} : réponse opérateur inexploitable, "
                    + "remboursement laissé PENDING (montant réservé) — vérifier chez l'opérateur",
                    reservation.refundId(), paymentId, e);
            paymentLogService.error(reserved, "REFUND_OUTCOME_UNKNOWN",
                    "Résultat du remboursement inconnu : à vérifier chez le prestataire", e.getClass().getSimpleName());
            return ledger.keepPending(reservation);
        }

        RefundResponse response = ledger.complete(reservation, result);
        if (result.status() == RefundStatus.FAILED) {
            paymentLogService.error(reserved, "REFUND_FAILED", result.failureReason(), result.rawResponse());
        }
        return response;
    }
}
