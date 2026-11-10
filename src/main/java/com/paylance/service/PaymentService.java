package com.paylance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.common.CorrelationContext;
import com.paylance.common.PhoneNumbers;
import com.paylance.domain.entity.Application;
import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.idempotency.IdempotencyService;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnectionResolver;
import com.paylance.provider.ProviderInitiateResult;
import com.paylance.provider.ProviderPayoutResult;
import com.paylance.repository.ApplicationRepository;
import com.paylance.repository.OrganizationRepository;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.RefundRepository;
import com.paylance.repository.spec.RefundSpecifications;
import com.paylance.web.dto.*;
import com.paylance.web.exception.PaymentNotFoundException;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrateur central : initiation, versement, consultation. Toute transition de statut passe
 * par {@link PaymentStatusTransitionService} pour garantir un historique systématique.
 *
 * <p>Toutes les opérations s'exécutent dans le tenant courant (organisation) : les lectures sont
 * filtrées par Hibernate, les écritures reçoivent automatiquement l'organisation.
 *
 * <p>Volontairement <b>non</b> {@code @Transactional} sur les méthodes qui appellent un
 * prestataire : on ne garde pas de connexion JDBC ouverte pendant un appel réseau sortant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int DEFAULT_EXPIRY_MINUTES = 30;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderConnectionResolver connectionResolver;
    private final ProviderAccountService providerAccountService;
    private final CustomerService customerService;
    private final IdempotencyService idempotencyService;
    private final PaymentLogService paymentLogService;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentNumberGenerator paymentNumberGenerator;
    private final ObjectMapper objectMapper;

    public PaymentInitiateResponse initiate(PaymentContext context, String idempotencyKey, PaymentInitiateRequest request) {
        validateIdempotencyKey(idempotencyKey);
        log.info("[PAYMENT_INIT_START] org={}, application={}, provider={}, amount={}, idempotencyKey={}",
                context.organizationId(), context.applicationId(), request.provider(), request.amount(), idempotencyKey);

        var existing = paymentRepository.findByApplicationIdAndIdempotencyKey(context.applicationId(), idempotencyKey);
        if (existing.isPresent()) {
            log.info("[PAYMENT_INIT_IDEMPOTENT_HIT] paymentId={}", existing.get().getId());
            return toInitiateResponse(existing.get(), null);
        }

        String lockScope = lockScope(context);
        String token = idempotencyService.acquireLock(lockScope, idempotencyKey);
        try {
            // Re-vérification après verrou (fenêtre entre le premier contrôle et l'acquisition).
            existing = paymentRepository.findByApplicationIdAndIdempotencyKey(context.applicationId(), idempotencyKey);
            if (existing.isPresent()) {
                return toInitiateResponse(existing.get(), null);
            }

            Application application = loadApplication(context.applicationId());
            ProviderAccount account = providerAccountService.selectForNewPayment(
                    request.provider(), context.environment(), request.providerAccountId());

            String successUrl = firstNonBlank(request.successUrl(), application != null ? application.getSuccessUrl() : null);
            String errorUrl = firstNonBlank(request.errorUrl(), application != null ? application.getErrorUrl() : null);
            if (successUrl == null || errorUrl == null) {
                throw new IllegalArgumentException(
                        "successUrl et errorUrl sont requis (dans la requête ou par défaut sur l'application)");
            }
            String callbackUrl = firstNonBlank(request.callbackUrl(), application != null ? application.getCallbackUrl() : null);

            Payment payment = createPendingPayment(context, idempotencyKey, account, request, callbackUrl, successUrl, errorUrl);
            paymentLogService.info(payment, "INITIATE_REQUEST", "Requête d'initiation reçue (" + context.actor() + ")", writeJson(request));
            return callProviderInitiate(payment);
        } finally {
            idempotencyService.release(lockScope, idempotencyKey, token);
        }
    }

    /**
     * Lien de paiement créé depuis le dashboard : même parcours que {@link #initiate}, avec une
     * clé d'idempotence générée (chaque création est un nouveau paiement).
     */
    public PaymentInitiateResponse createPaymentLink(PaymentContext context, PaymentLinkRequest request) {
        PaymentInitiateRequest initiate = new PaymentInitiateRequest(
                request.provider(), request.providerAccountId(), request.amount(), request.currency(),
                request.payerPhone(), request.payerName(), null, null, null, null,
                request.successUrl(), request.errorUrl(), request.expiresInMinutes(), null);
        return initiate(context, "link-" + UUID.randomUUID(), initiate);
    }

    /** Versement direct (payout) vers le numéro du bénéficiaire, sans interaction utilisateur. */
    public PaymentInitiateResponse payout(PaymentContext context, String idempotencyKey, PaymentInitiateRequest request) {
        validateIdempotencyKey(idempotencyKey);
        var existing = paymentRepository.findByApplicationIdAndIdempotencyKey(context.applicationId(), idempotencyKey);
        if (existing.isPresent()) {
            return toInitiateResponse(existing.get(), null);
        }
        String lockScope = lockScope(context);
        String token = idempotencyService.acquireLock(lockScope, idempotencyKey);
        try {
            existing = paymentRepository.findByApplicationIdAndIdempotencyKey(context.applicationId(), idempotencyKey);
            if (existing.isPresent()) {
                return toInitiateResponse(existing.get(), null);
            }
            ProviderAccount account = providerAccountService.selectForNewPayment(
                    request.provider(), context.environment(), request.providerAccountId());
            Payment payment = createPendingPayment(context, idempotencyKey, account, request, null, null, null);
            paymentLogService.info(payment, "PAYOUT_REQUEST", "Requête de versement reçue (" + context.actor() + ")", writeJson(request));

            PaymentProviderClient client = providerRegistry.resolve(payment.getProvider());
            try {
                long start = System.currentTimeMillis();
                ProviderPayoutResult result = client.payout(connectionResolver.forPayment(payment), payment);
                long duration = System.currentTimeMillis() - start;
                payment.setProviderTransactionId(result.providerTransactionId());
                payment.setProviderRawResponse(result.rawResponse());
                if (result.failureReason() != null) {
                    payment.setFailureReason(result.failureReason());
                }
                paymentLogService.info(payment, "PROVIDER_PAYOUT_RESPONSE", "Réponse de versement reçue", result.rawResponse(), duration);
                payment = statusTransitionService.transitionStatus(payment, result.status(), "Versement direct prestataire", TriggeredBy.API);
                payment = paymentRepository.save(payment);
                return toInitiateResponse(payment, null);
            } catch (Exception e) {
                paymentLogService.error(payment, "PROVIDER_PAYOUT_FAILED", e.getMessage(), null);
                payment = statusTransitionService.transitionStatus(payment, PaymentStatus.FAILED,
                        "Erreur communication versement : " + e.getMessage(), TriggeredBy.API);
                paymentRepository.save(payment);
                throw e;
            }
        } finally {
            idempotencyService.release(lockScope, idempotencyKey, token);
        }
    }

    private PaymentInitiateResponse callProviderInitiate(Payment payment) {
        PaymentProviderClient client = providerRegistry.resolve(payment.getProvider());
        try {
            paymentLogService.info(payment, "PROVIDER_CALL", "Appel initiation vers " + payment.getProvider());
            long start = System.currentTimeMillis();
            ProviderInitiateResult result = client.initiate(connectionResolver.forPayment(payment), payment);
            long duration = System.currentTimeMillis() - start;

            payment.setProviderTransactionId(result.providerTransactionId());
            payment.setProviderRawResponse(result.rawResponse());
            paymentLogService.info(payment, "PROVIDER_RESPONSE", "Réponse d'initiation reçue", result.rawResponse(), duration);

            payment = statusTransitionService.transitionStatus(payment, result.status(), "Initiation prestataire", TriggeredBy.API);
            payment = paymentRepository.save(payment);
            log.info("[PAYMENT_INIT_COMPLETE] paymentId={} status={} ({}ms chez le prestataire)", payment.getId(), payment.getStatus(), duration);
            return toInitiateResponse(payment, result.checkoutReference());
        } catch (Exception e) {
            log.error("[PAYMENT_INIT_PROVIDER_FAILED] paymentId={} : {}", payment.getId(), e.getMessage());
            paymentLogService.error(payment, "PROVIDER_CALL_FAILED", e.getMessage(), null);
            payment = statusTransitionService.transitionStatus(payment, PaymentStatus.FAILED,
                    "Erreur communication prestataire : " + e.getMessage(), TriggeredBy.API);
            paymentRepository.save(payment);
            throw e;
        }
    }

    private Payment createPendingPayment(PaymentContext context, String idempotencyKey, ProviderAccount account,
                                         PaymentInitiateRequest request, String callbackUrl,
                                         String successUrl, String errorUrl) {
        String customerId = customerService.upsert(request.payerPhone(), request.payerName(),
                request.payerEmail(), request.customerExternalId());
        Payment payment = Payment.builder()
                .paymentNumber(paymentNumberGenerator.generate())
                .applicationId(context.applicationId())
                .providerAccountId(account.getId())
                .customerId(customerId)
                .environment(account.getEnvironment())
                .idempotencyKey(idempotencyKey)
                .correlationId(CorrelationContext.current())
                .externalReference(request.externalReference())
                .callbackUrl(callbackUrl)
                .successUrl(successUrl)
                .errorUrl(errorUrl)
                .metadata(writeJson(request.metadata()))
                .provider(account.getProvider())
                .amount(request.amount())
                .currency(resolveCurrency(context.organizationId(), request.currency()))
                .payerPhone(PhoneNumbers.normalize(request.payerPhone()))
                .payerName(request.payerName())
                .status(PaymentStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(
                        request.expiresInMinutes() != null ? request.expiresInMinutes() : DEFAULT_EXPIRY_MINUTES))
                .build();
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment getPayment(String paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /** Accepte l'identifiant technique ou le numéro lisible (PL-...). */
    @Transactional(readOnly = true)
    public Payment getPaymentByIdOrNumber(String identifier) {
        return paymentRepository.findById(identifier)
                .or(() -> paymentRepository.findByPaymentNumber(identifier))
                .orElseThrow(() -> new PaymentNotFoundException(identifier));
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponse getStatus(String paymentId) {
        return toStatusResponse(getPayment(paymentId));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByPaymentId(String paymentId) {
        getPayment(paymentId);
        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(this::toRefundResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<RefundResponse> searchRefunds(String query, String paymentNumber, String phone, RefundStatus status,
                                              Long minAmount, Long maxAmount, LocalDateTime from, LocalDateTime to,
                                              Pageable pageable) {
        var spec = RefundSpecifications.filter(query, paymentNumber, phone, status, minAmount, maxAmount, from, to);
        return refundRepository.findAll(spec, pageable).map(this::toRefundResponse);
    }

    public RefundResponse toRefundResponse(Refund refund) {
        Payment payment = refund.getPayment();
        return new RefundResponse(
                refund.getId(),
                payment != null ? payment.getId() : null,
                payment != null ? payment.getPaymentNumber() : null,
                payment != null ? payment.getPayerPhone() : null,
                refund.getAmount(),
                refund.getStatus(),
                refund.getReason(),
                refund.getFailureReason(),
                refund.getProviderRefundId(),
                refund.getRequestedBy(),
                refund.getCreatedAt());
    }

    public PaymentStatusResponse toStatusResponse(Payment payment) {
        return new PaymentStatusResponse(
                payment.getId(), payment.getPaymentNumber(), payment.getCorrelationId(),
                payment.getApplicationId(), payment.getProviderAccountId(), payment.getCustomerId(),
                payment.getEnvironment(), payment.getProvider(), payment.getStatus(),
                payment.getAmount(), payment.getCurrency(), payment.getPayerPhone(), payment.getActualPayerPhone(),
                payment.getExternalReference(), payment.getFailureReason(), toInstant(payment.getPaidAt()),
                toInstant(payment.getExpiresAt()), toInstant(payment.getCreatedAt()), toInstant(payment.getUpdatedAt()));
    }

    PaymentInitiateResponse toInitiateResponse(Payment payment, String checkoutReference) {
        return new PaymentInitiateResponse(
                payment.getId(), payment.getPaymentNumber(), payment.getCorrelationId(), payment.getStatus(), payment.getProvider(),
                payment.getAmount(), payment.getCurrency(), payment.getPayerPhone(), payment.getExternalReference(),
                checkoutReference, toInstant(payment.getExpiresAt()), toInstant(payment.getCreatedAt()));
    }

    private Application loadApplication(String applicationId) {
        if (applicationId == null) {
            return null;
        }
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application", applicationId));
    }

    private String resolveCurrency(String organizationId, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        return organizationRepository.findById(organizationId).map(Organization::getDefaultCurrency).orElse("XOF");
    }

    private static String lockScope(PaymentContext context) {
        return context.organizationId() + ":" + (context.applicationId() != null ? context.applicationId() : "-");
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("En-tête Idempotency-Key requis");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key trop long (100 caractères max)");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    static String formatAmount(Long amountInCents, String currency) {
        if (amountInCents == null) {
            return "0";
        }
        String plain = BigDecimal.valueOf(amountInCents, 2).stripTrailingZeros().toPlainString();
        return currency != null && !currency.isBlank() ? plain + " " + currency : plain;
    }

    /** Le JVM tourne en UTC (cf. PaylanceApplication#main) : on ajoute juste l'offset explicite. */
    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
