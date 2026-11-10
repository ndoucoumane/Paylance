package com.paylance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.*;
import com.paylance.idempotency.IdempotencyService;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnectionResolver;
import com.paylance.provider.ProviderInitiateResult;
import com.paylance.provider.ProviderPayoutResult;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.RefundRepository;
import com.paylance.web.dto.*;
import com.paylance.web.exception.PaymentNotFoundException;
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
 * Orchestrateur central : initiation, consultation de statut, remboursement.
 * Toute transition de statut passe par {@link PaymentStatusTransitionService} pour garantir
 * qu'un historique est systématiquement écrit.
 *
 * <p>Volontairement <b>non</b> {@code @Transactional} sur {@link #initiate} / {@link #refund} :
 * ces méthodes font un appel réseau sortant vers le provider (Wave/Orange Money), qu'il ne
 * faut pas exécuter en tenant une transaction/connexion JDBC ouverte. Chaque écriture
 * intermédiaire (création du paiement PENDING, transition de statut) est donc committée
 * individuellement dans sa propre petite transaction, ce qui garantit aussi que
 * {@link PaymentLogService} (REQUIRES_NEW) référence toujours un {@code payment_id} déjà
 * committé — sinon la contrainte de clé étrangère échouerait.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final int DEFAULT_EXPIRY_MINUTES = 30;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderConnectionResolver connectionResolver;
    private final IdempotencyService idempotencyService;
    private final PaymentLogService paymentLogService;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentNumberGenerator paymentNumberGenerator;
    private final ObjectMapper objectMapper;

    public PaymentInitiateResponse initiate(Platform platform, PaymentInitiateRequest request) {
        long totalStart = System.currentTimeMillis();
        log.info("[PAYMENT_INIT_START] Début initiation paiement: platform={}, provider={}, amount={}, idempotencyKey={}, externalRef={}",
                platform, request.provider(), formatAmount(request.amount(), request.currency()), request.idempotencyKey(), request.externalReference());

        long t0 = System.currentTimeMillis();
        var existing = paymentRepository.findByPlatformAndIdempotencyKey(platform, request.idempotencyKey());
        long dbCheck1Duration = System.currentTimeMillis() - t0;
        log.info("[STEP 1 - IDEMPOTENCY_DB_CHECK_1] Vérification idempotence initiale en {}ms (trouvé={})",
                dbCheck1Duration, existing.isPresent());

        if (existing.isPresent()) {
            long total = System.currentTimeMillis() - totalStart;
            log.info("[PAYMENT_INIT_IDEMPOTENT_HIT] Paiement existant retourné en {}ms pour idempotencyKey={} (paymentId={})",
                    total, request.idempotencyKey(), existing.get().getId());
            return toInitiateResponse(existing.get());
        }

        long tLock = System.currentTimeMillis();
        String token = idempotencyService.acquireLock(platform.name(), request.idempotencyKey());
        long redisLockDuration = System.currentTimeMillis() - tLock;
        log.info("[STEP 2 - REDIS_LOCK_ACQUIRED] Verrou d'idempotence acquis en {}ms", redisLockDuration);

        try {
            // re-vérifier après acquisition du verrou (fenêtre de course entre le check et le lock)
            long tCheck2 = System.currentTimeMillis();
            existing = paymentRepository.findByPlatformAndIdempotencyKey(platform, request.idempotencyKey());
            long dbCheck2Duration = System.currentTimeMillis() - tCheck2;
            log.info("[STEP 3 - IDEMPOTENCY_DB_CHECK_2] Re-vérification après verrou en {}ms (trouvé={})",
                    dbCheck2Duration, existing.isPresent());

            if (existing.isPresent()) {
                long total = System.currentTimeMillis() - totalStart;
                log.info("[PAYMENT_INIT_IDEMPOTENT_HIT_AFTER_LOCK] Paiement existant retourné en {}ms pour idempotencyKey={} (paymentId={})",
                        total, request.idempotencyKey(), existing.get().getId());
                return toInitiateResponse(existing.get());
            }

            long tCreate = System.currentTimeMillis();
            Payment payment = createPendingPayment(platform, request);
            long dbCreateDuration = System.currentTimeMillis() - tCreate;
            log.info("[STEP 4 - DB_CREATE_PENDING] Paiement PENDING créé en base en {}ms (paymentId={}, paymentNumber={})",
                    dbCreateDuration, payment.getId(), payment.getPaymentNumber());

            long tAudit = System.currentTimeMillis();
            paymentLogService.info(payment, "INITIATE_REQUEST", "Requête d'initiation reçue de " + platform, writeJson(request));
            long auditLogDuration = System.currentTimeMillis() - tAudit;
            log.info("[STEP 5 - PAYMENT_LOG_AUDIT] Log d'audit INITIATE_REQUEST persisté en {}ms", auditLogDuration);

            long tResolve = System.currentTimeMillis();
            PaymentProviderClient providerClient = providerRegistry.resolve(request.provider());
            long resolveDuration = System.currentTimeMillis() - tResolve;
            log.info("[STEP 6 - PROVIDER_RESOLVE] ProviderClient {} résolu en {}ms", request.provider(), resolveDuration);

            try {
                paymentLogService.info(payment, "PROVIDER_CALL", "Appel initiation vers " + request.provider());

                long tProvider = System.currentTimeMillis();
                log.info("[STEP 7 - PROVIDER_CALL_START] Envoi requête vers provider {}", request.provider());
                ProviderInitiateResult result = providerClient.initiate(connectionResolver.forPayment(payment), payment);
                long providerDuration = System.currentTimeMillis() - tProvider;
                log.info("[STEP 7 - PROVIDER_CALL_END] Réponse reçue du provider {} en {}ms (providerTxId={}, status={})",
                        request.provider(), providerDuration, result.providerTransactionId(), result.status());

                payment.setProviderTransactionId(result.providerTransactionId());
                payment.setProviderRawResponse(result.rawResponse());
                paymentLogService.info(payment, "PROVIDER_RESPONSE", "Réponse d'initiation reçue de " + request.provider(), result.rawResponse(), providerDuration);

                long tTransition = System.currentTimeMillis();
                payment = statusTransitionService.transitionStatus(payment, result.status(), "Initiation provider", TriggeredBy.API);
                long transitionDuration = System.currentTimeMillis() - tTransition;
                log.info("[STEP 8 - STATUS_TRANSITION] Transition de statut exécutée en {}ms", transitionDuration);

                long tSaveFinal = System.currentTimeMillis();
                payment = paymentRepository.save(payment);
                long saveFinalDuration = System.currentTimeMillis() - tSaveFinal;
                log.info("[STEP 9 - DB_SAVE_FINAL] Sauvegarde finale du paiement en {}ms", saveFinalDuration);

                long totalDuration = System.currentTimeMillis() - totalStart;
                log.info("[PAYMENT_INIT_COMPLETE] Initiation paiement terminée avec succès en {}ms (RedisLock: {}ms, DBCreate: {}ms, ProviderHTTP: {}ms, Transition: {}ms, FinalSave: {}ms) [paymentId={}]",
                        totalDuration, redisLockDuration, dbCreateDuration, providerDuration, transitionDuration, saveFinalDuration, payment.getId());

                return toInitiateResponse(payment, result.checkoutReference());
            } catch (Exception e) {
                long totalFailureDuration = System.currentTimeMillis() - totalStart;
                log.error("[STEP 7 - PROVIDER_CALL_FAILED] Échec communication provider {} après {}ms: {}",
                        request.provider(), totalFailureDuration, e.getMessage(), e);
                paymentLogService.error(payment, "PROVIDER_CALL_FAILED", e.getMessage(), null);
                payment = statusTransitionService.transitionStatus(payment, PaymentStatus.FAILED, "Erreur communication provider : " + e.getMessage(), TriggeredBy.API);
                paymentRepository.save(payment);
                throw e;
            }
        } finally {
            long tRelease = System.currentTimeMillis();
            idempotencyService.release(platform.name(), request.idempotencyKey(), token);
            long releaseDuration = System.currentTimeMillis() - tRelease;
            log.info("[STEP 10 - REDIS_RELEASE] Verrou Redis libéré en {}ms", releaseDuration);
        }
    }

    /**
     * Crée un paiement à l'initiative d'un agent (dashboard admin) — ex: lien de paiement Wave
     * partagé manuellement à un client qui ne passe pas par Afrisurgelée/Kssid/Fleet360. Réutilise
     * {@link #initiate} tel quel : le webhook qui confirmera plus tard ce paiement le retrouvera
     * normalement par providerTransactionId, sans ambiguïté d'attribution (contrairement à un
     * paiement reçu directement chez le provider sans création préalable côté Paylance).
     */
    public PaymentInitiateResponse createPaymentLink(
            Platform platform, PaymentProvider provider, Long amount, String currency,
            String payerPhone, String payerName, Integer expiresInMinutes) {
        if (platform == Platform.MANUAL) {
            log.warn("[PAYMENT_LINK_REJECTED] Tentative de création de lien de paiement avec plateforme MANUAL");
            throw new IllegalArgumentException("MANUAL n'est pas une plateforme valide pour un lien de paiement");
        }
        log.info("[PAYMENT_LINK_START] Création lien de paiement: platform={}, provider={}, amount={} {}",
                platform, provider, amount, currency);
        PaymentInitiateRequest request = new PaymentInitiateRequest(
                "admin-link-" + UUID.randomUUID(), provider, amount, currency,
                payerPhone, payerName, null, null, expiresInMinutes, null, null, null);
        return initiate(platform, request);
    }

    /**
     * Exécute un virement direct sortant (Payout / Déboursement) vers le numéro de téléphone cible sans interaction utilisateur.
     */
    public PaymentInitiateResponse payout(Platform platform, PaymentInitiateRequest request) {
        long totalStart = System.currentTimeMillis();
        log.info("[PAYMENT_PAYOUT_START] Début virement direct: platform={}, provider={}, amount={}, idempotencyKey={}, recipientPhone={}",
                platform, request.provider(), formatAmount(request.amount(), request.currency()), request.idempotencyKey(), request.payerPhone());

        long t0 = System.currentTimeMillis();
        var existing = paymentRepository.findByPlatformAndIdempotencyKey(platform, request.idempotencyKey());
        long dbCheck1Duration = System.currentTimeMillis() - t0;

        if (existing.isPresent()) {
            long total = System.currentTimeMillis() - totalStart;
            log.info("[PAYMENT_PAYOUT_IDEMPOTENT_HIT] Virement existant retourné en {}ms pour idempotencyKey={} (paymentId={})",
                    total, request.idempotencyKey(), existing.get().getId());
            return toInitiateResponse(existing.get());
        }

        long tLock = System.currentTimeMillis();
        String token = idempotencyService.acquireLock(platform.name(), request.idempotencyKey());
        long redisLockDuration = System.currentTimeMillis() - tLock;

        try {
            existing = paymentRepository.findByPlatformAndIdempotencyKey(platform, request.idempotencyKey());
            if (existing.isPresent()) {
                long total = System.currentTimeMillis() - totalStart;
                log.info("[PAYMENT_PAYOUT_IDEMPOTENT_HIT_AFTER_LOCK] Virement existant retourné en {}ms pour idempotencyKey={} (paymentId={})",
                        total, request.idempotencyKey(), existing.get().getId());
                return toInitiateResponse(existing.get());
            }

            Payment payment = createPendingPayment(platform, request);
            paymentLogService.info(payment, "PAYOUT_REQUEST", "Requête de virement direct reçue de " + platform, writeJson(request));

            PaymentProviderClient providerClient = providerRegistry.resolve(request.provider());

            try {
                paymentLogService.info(payment, "PROVIDER_PAYOUT_CALL", "Appel virement sortant vers " + request.provider());

                long tProvider = System.currentTimeMillis();
                ProviderPayoutResult result = providerClient.payout(connectionResolver.forPayment(payment), payment);
                long providerDuration = System.currentTimeMillis() - tProvider;

                payment.setProviderTransactionId(result.providerTransactionId());
                payment.setProviderRawResponse(result.rawResponse());
                if (result.failureReason() != null) {
                    payment.setFailureReason(result.failureReason());
                }
                if (result.status() == PaymentStatus.SUCCESS) {
                    payment.setPaidAt(LocalDateTime.now());
                }
                paymentLogService.info(payment, "PROVIDER_PAYOUT_RESPONSE", "Réponse virement reçue de " + request.provider(), result.rawResponse(), providerDuration);

                payment = statusTransitionService.transitionStatus(payment, result.status(), "Virement direct provider (" + request.provider() + ")", TriggeredBy.API);
                payment = paymentRepository.save(payment);

                long totalDuration = System.currentTimeMillis() - totalStart;
                log.info("[PAYMENT_PAYOUT_COMPLETE] Virement terminé en {}ms pour paymentId={}, status={}",
                        totalDuration, payment.getId(), result.status());

                return toInitiateResponse(payment, null);
            } catch (Exception e) {
                long totalFailureDuration = System.currentTimeMillis() - totalStart;
                log.error("[PAYMENT_PAYOUT_FAILED] Échec communication provider {} après {}ms: {}",
                        request.provider(), totalFailureDuration, e.getMessage(), e);
                paymentLogService.error(payment, "PROVIDER_PAYOUT_FAILED", e.getMessage(), null);
                payment = statusTransitionService.transitionStatus(payment, PaymentStatus.FAILED, "Erreur communication virement : " + e.getMessage(), TriggeredBy.API);
                paymentRepository.save(payment);
                throw e;
            }
        } finally {
            idempotencyService.release(platform.name(), request.idempotencyKey(), token);
        }
    }

    // Pas de @Transactional ici : un appel this.xxx(...) depuis initiate() ne passerait pas par
    // le proxy Spring de toute façon (auto-invocation) et n'aurait donc aucun effet. La méthode
    // reste correcte : paymentRepository.save(...) committe seul dans sa propre mini-transaction
    // (SimpleJpaRepository porte déjà @Transactional), ce qui est exactement ce qu'on veut ici.
    private Payment createPendingPayment(Platform platform, PaymentInitiateRequest request) {
        long tGen = System.currentTimeMillis();
        String paymentNumber = paymentNumberGenerator.generate();
        long genDuration = System.currentTimeMillis() - tGen;
        log.info("[CREATE_PENDING_PAYMENT_GEN] Numéro de paiement généré en {}ms : {}", genDuration, paymentNumber);

        Payment payment = Payment.builder()
                .paymentNumber(paymentNumber)
                .idempotencyKey(request.idempotencyKey())
                .correlationId(CorrelationContext.current())
                .platform(platform)
                .externalReference(request.externalReference())
                .callbackUrl(request.callbackUrl())
                .metadata(writeJson(request.metadata()))
                .provider(request.provider())
                .amount(request.amount())
                .currency(request.currency() != null ? request.currency() : "XOF")
                .payerPhone(request.payerPhone())
                .payerName(request.payerName())
                .successUrl(request.successUrl())
                .errorUrl(request.errorUrl())
                .status(PaymentStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(
                        request.expiresInMinutes() != null ? request.expiresInMinutes() : DEFAULT_EXPIRY_MINUTES))
                .build();
        long tSave = System.currentTimeMillis();
        Payment saved = paymentRepository.save(payment);
        long saveDuration = System.currentTimeMillis() - tSave;
        log.info("[CREATE_PENDING_PAYMENT_SAVE] Paiement PENDING sauvegardé en {}ms (paymentId={})", saveDuration, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponse getStatus(String paymentId) {
        long start = System.currentTimeMillis();
        log.info("[PAYMENT_GET_STATUS_START] Consultation statut pour paymentId={}", paymentId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        long duration = System.currentTimeMillis() - start;
        log.info("[PAYMENT_GET_STATUS_COMPLETE] Statut récupéré en {}ms pour paymentId={} (status={}, provider={})",
                duration, paymentId, payment.getStatus(), payment.getProvider());
        return toStatusResponse(payment);
    }

    public RefundResponse refund(String paymentId, RefundRequest request) {
        long totalStart = System.currentTimeMillis();
        log.info("[REFUND_START] Demande de remboursement reçue pour paymentId={}, amount={}, reason={}",
                paymentId, request.amount(), request.reason());

        long tFind = System.currentTimeMillis();
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        long findDuration = System.currentTimeMillis() - tFind;
        log.info("[REFUND_PAYMENT_FOUND] Paiement trouvé en {}ms (status={}, amount={} {})",
                findDuration, payment.getStatus(), payment.getAmount(), payment.getCurrency());

        if (payment.getStatus() != PaymentStatus.SUCCESS && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            log.warn("[REFUND_INVALID_STATUS] Impossible de rembourser un paiement au statut {}", payment.getStatus());
            throw new IllegalStateException("Impossible de rembourser un paiement au statut " + payment.getStatus());
        }

        // Requête indépendante plutôt que payment.getRefunds() : la collection est lazy et
        // "payment" est déjà détaché à ce stade (méthode volontairement non @Transactional,
        // voir Javadoc de la classe) — y accéder lèverait une LazyInitializationException.
        long tHistory = System.currentTimeMillis();
        long alreadyRefunded = refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCESS)
                .mapToLong(Refund::getAmount)
                .sum();
        long historyDuration = System.currentTimeMillis() - tHistory;
        long remaining = payment.getAmount() - alreadyRefunded;
        long refundAmount = request.amount() != null ? request.amount() : remaining;
        if (refundAmount > remaining) {
            throw new IllegalArgumentException("Le montant du remboursement dépasse le solde remboursable (" + formatAmount(remaining, payment.getCurrency()) + ")");
        }

        long tSavePending = System.currentTimeMillis();
        Refund refund = Refund.builder()
                .payment(payment)
                .amount(refundAmount)
                .reason(request.reason())
                .status(RefundStatus.PENDING)
                .requestedBy("API")
                .build();
        refund = refundRepository.save(refund);
        long savePendingDuration = System.currentTimeMillis() - tSavePending;
        paymentLogService.info(payment, "REFUND_REQUESTED", "Remboursement demandé : " + formatAmount(refundAmount, payment.getCurrency()), request.reason());

        long tProvider = System.currentTimeMillis();
        PaymentProviderClient providerClient = providerRegistry.resolve(payment.getProvider());
        log.info("[REFUND_PROVIDER_CALL_START] Appel provider {} refund pour paymentId={}, amount={}",
                payment.getProvider(), paymentId, refundAmount);
        ProviderRefundResult result = providerClient.refund(connectionResolver.forPayment(payment), payment, refundAmount, request.reason());
        long providerDuration = System.currentTimeMillis() - tProvider;
        log.info("[REFUND_PROVIDER_CALL_END] Réponse provider {} refund reçue en {}ms (status={}, providerRefundId={})",
                payment.getProvider(), providerDuration, result.status(), result.providerRefundId());

        refund.setStatus(result.status());
        refund.setProviderRefundId(result.providerRefundId());
        refund.setProviderRawResponse(result.rawResponse());
        refund.setFailureReason(result.failureReason());
        refund = refundRepository.save(refund);

        if (result.status() == RefundStatus.SUCCESS) {
            long totalRefunded = alreadyRefunded + refundAmount;
            PaymentStatus newStatus = totalRefunded >= payment.getAmount() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            payment = statusTransitionService.transitionStatus(payment, newStatus, "Remboursement confirmé par le provider", TriggeredBy.API);
            paymentLogService.info(payment, "REFUND_SUCCESS", "Remboursement effectué : " + formatAmount(refundAmount, payment.getCurrency()), result.rawResponse(), providerDuration);
        } else {
            paymentLogService.error(payment, "REFUND_FAILED", result.failureReason(), result.rawResponse(), providerDuration);
        }

        long totalRefundDuration = System.currentTimeMillis() - totalStart;
        log.info("[REFUND_COMPLETE] Remboursement terminé en {}ms (findPay: {}ms, history: {}ms, savePending: {}ms, providerHTTP: {}ms) pour paymentId={}, refundId={}, status={}",
                totalRefundDuration, findDuration, historyDuration, savePendingDuration, providerDuration, paymentId, refund.getId(), refund.getStatus());

        return toRefundResponse(refund, payment);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsByPaymentId(String paymentId) {
        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId).stream()
                .map(this::toRefundResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<RefundResponse> searchRefunds(
            String query,
            String paymentNumber,
            String phone,
            RefundStatus status,
            Long minAmount,
            Long maxAmount,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    ) {
        org.springframework.data.jpa.domain.Specification<Refund> spec = com.paylance.repository.spec.RefundSpecifications.filter(
                query, paymentNumber, phone, status, minAmount, maxAmount, from, to
        );
        return refundRepository.findAll(spec, pageable).map(this::toRefundResponse);
    }

    @Transactional(readOnly = true)
    public Page<RefundResponse> listRefunds(RefundStatus status, Pageable pageable) {
        return searchRefunds(null, null, null, status, null, null, null, null, pageable);
    }

    public RefundResponse toRefundResponse(Refund refund) {
        return toRefundResponse(refund, null);
    }

    public RefundResponse toRefundResponse(Refund refund, Payment payment) {
        String paymentId = null;
        String paymentNumber = null;
        String payerPhone = null;
        if (payment != null) {
            paymentId = payment.getId();
            paymentNumber = payment.getPaymentNumber();
            payerPhone = payment.getPayerPhone();
        } else if (refund.getPayment() != null) {
            paymentId = refund.getPayment().getId();
            try {
                paymentNumber = refund.getPayment().getPaymentNumber();
                payerPhone = refund.getPayment().getPayerPhone();
            } catch (Exception ignored) {
                // Tolère un proxy non initialisé hors session
            }
        }
        return new RefundResponse(
                refund.getId(),
                paymentId,
                paymentNumber,
                payerPhone,
                refund.getAmount(),
                refund.getStatus(),
                refund.getReason(),
                refund.getFailureReason(),
                refund.getProviderRefundId(),
                refund.getRequestedBy(),
                refund.getCreatedAt()
        );
    }

    private String formatAmount(Long amountInCents, String currency) {
        if (amountInCents == null) {
            return "0";
        }
        String plain = BigDecimal.valueOf(amountInCents, 2).stripTrailingZeros().toPlainString();
        return currency != null && !currency.isBlank() ? plain + " " + currency : plain;
    }

    private PaymentInitiateResponse toInitiateResponse(Payment payment) {
        return toInitiateResponse(payment, null);
    }

    private PaymentInitiateResponse toInitiateResponse(Payment payment, String checkoutReference) {
        return new PaymentInitiateResponse(
                payment.getId(), payment.getPaymentNumber(), payment.getCorrelationId(), payment.getStatus(), payment.getProvider(),
                payment.getAmount(), payment.getCurrency(), payment.getPayerPhone(), payment.getExternalReference(),
                checkoutReference, toInstant(payment.getExpiresAt()), toInstant(payment.getCreatedAt()));
    }

    public PaymentStatusResponse toStatusResponse(Payment payment) {
        return new PaymentStatusResponse(
                payment.getId(), payment.getPaymentNumber(), payment.getCorrelationId(), payment.getPlatform(), payment.getProvider(), payment.getStatus(),
                payment.getAmount(), payment.getCurrency(), payment.getPayerPhone(), payment.getActualPayerPhone(), payment.getExternalReference(),
                payment.getFailureReason(), toInstant(payment.getPaidAt()), toInstant(payment.getExpiresAt()),
                toInstant(payment.getCreatedAt()), toInstant(payment.getUpdatedAt()));
    }

    /** Le JVM tourne en UTC (cf. PaylanceApplication#main) : les LocalDateTime persistés
     * (LocalDateTime.now()/@CreationTimestamp) sont donc déjà des instants UTC, il ne manque
     * qu'un offset explicite pour que l'API ne laisse plus les clients le deviner. */
    private static Instant toInstant(LocalDateTime value) {
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
