package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.ScheduledPaymentRepository;
import com.paylance.repository.spec.ScheduledPaymentSpecifications;
import com.paylance.tenant.TenantContext;
import com.paylance.web.dto.PaymentInitiateRequest;
import com.paylance.web.dto.PaymentInitiateResponse;
import com.paylance.web.dto.ScheduledPaymentCreateRequest;
import com.paylance.web.dto.ScheduledPaymentResponse;
import com.paylance.web.exception.ProviderCommunicationException;
import com.paylance.web.exception.ScheduledPaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Versements (payouts) programmés depuis le dashboard : enregistrés au statut {@code SCHEDULED},
 * exécutés via {@link PaymentService#payout} par le job planifié dès que {@code scheduledAt} est
 * atteint. La réservation est atomique ({@link DueWorkClaimer}) : pas de double exécution entre
 * instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledPaymentService {

    private static final int CLAIM_BATCH_SIZE = 50;

    private final ScheduledPaymentRepository scheduledPaymentRepository;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final ProviderAccountService providerAccountService;
    private final DueWorkClaimer dueWorkClaimer;

    public ScheduledPaymentResponse create(ScheduledPaymentCreateRequest request) {
        ProviderAccount account = providerAccountService.selectForNewPayment(
                request.provider(), null, request.providerAccountId());
        ScheduledPayment scheduledPayment = scheduledPaymentRepository.save(ScheduledPayment.builder()
                .provider(account.getProvider())
                .providerAccountId(account.getId())
                .amount(request.amount())
                .payerPhone(request.payerPhone())
                .scheduledAt(request.scheduledAt())
                .externalReference(request.externalReference())
                .note(request.note())
                .status(ScheduledPaymentStatus.SCHEDULED)
                .build());
        return toResponse(scheduledPayment);
    }

    public Page<ScheduledPaymentResponse> search(String query, String paymentNumber, String phone,
                                                 ScheduledPaymentStatus status, PaymentProvider provider,
                                                 String providerAccountId, Long minAmount, Long maxAmount,
                                                 LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Set<String> matchedPaymentIds = new HashSet<>();
        if (paymentNumber != null && !paymentNumber.isBlank()) {
            paymentRepository.findByPaymentNumber(paymentNumber.trim()).ifPresent(p -> matchedPaymentIds.add(p.getId()));
        }
        if (query != null && !query.isBlank()) {
            paymentRepository.findByPaymentNumber(query.trim()).ifPresent(p -> matchedPaymentIds.add(p.getId()));
        }
        var spec = ScheduledPaymentSpecifications.filter(query, phone, status, provider, providerAccountId,
                minAmount, maxAmount, from, to, matchedPaymentIds);
        Page<ScheduledPayment> page = scheduledPaymentRepository.findAll(spec, pageable);

        Set<String> paymentIds = page.getContent().stream()
                .map(ScheduledPayment::getPaymentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> paymentNumbers = paymentIds.isEmpty() ? Map.of()
                : paymentRepository.findAllById(paymentIds).stream()
                .filter(p -> p.getPaymentNumber() != null)
                .collect(Collectors.toMap(Payment::getId, Payment::getPaymentNumber, (a, b) -> a));
        return page.map(sp -> toResponse(sp, sp.getPaymentId() != null ? paymentNumbers.get(sp.getPaymentId()) : null));
    }

    public ScheduledPaymentResponse getById(String id) {
        return toResponse(find(id));
    }

    public ScheduledPaymentResponse cancel(String id) {
        ScheduledPayment scheduledPayment = find(id);
        if (scheduledPayment.getStatus() != ScheduledPaymentStatus.SCHEDULED) {
            throw new IllegalStateException("Impossible d'annuler un versement programmé au statut " + scheduledPayment.getStatus());
        }
        scheduledPayment.setStatus(ScheduledPaymentStatus.CANCELLED);
        return toResponse(scheduledPaymentRepository.save(scheduledPayment));
    }

    @Scheduled(fixedDelayString = "${paylance.scheduled-payments.interval-ms:60000}")
    public void executeDuePayments() {
        List<DueWorkClaimer.ClaimedItem> claimed = dueWorkClaimer.claimScheduledPayments(LocalDateTime.now(), CLAIM_BATCH_SIZE);
        if (claimed.isEmpty()) {
            return;
        }
        log.info("[SCHEDULED_PAYMENTS_JOB_START] {} versement(s) programmé(s) réservé(s)", claimed.size());
        claimed.forEach(item -> TenantContext.runAs(item.organizationId(), () -> executeOne(item.id())));
    }

    /** Exécute un versement déjà réservé (statut EXECUTING) dans son tenant. */
    void executeOne(String scheduledPaymentId) {
        ScheduledPayment scheduledPayment = find(scheduledPaymentId);
        CorrelationContext.set(UUID.randomUUID().toString());
        try {
            PaymentInitiateRequest request = new PaymentInitiateRequest(
                    scheduledPayment.getProvider(), scheduledPayment.getProviderAccountId(), scheduledPayment.getAmount(),
                    null, scheduledPayment.getPayerPhone(), null, null, null, scheduledPayment.getExternalReference(),
                    null, null, null, null,
                    scheduledPayment.getNote() != null ? Map.of("note", scheduledPayment.getNote()) : null);
            PaymentInitiateResponse response = paymentService.payout(
                    PaymentContext.forSystem(scheduledPayment.getOrganizationId(), "scheduled-payment"),
                    "SCHEDULED-" + scheduledPayment.getId(), request);

            scheduledPayment.setPaymentId(response.paymentId());
            if (response.status() == PaymentStatus.FAILED) {
                scheduledPayment.setStatus(ScheduledPaymentStatus.FAILED);
                String reason = paymentRepository.findById(response.paymentId()).map(Payment::getFailureReason).orElse(null);
                scheduledPayment.setFailureReason(reason != null && !reason.isBlank() ? reason : "Échec du versement prestataire");
            } else {
                scheduledPayment.setStatus(ScheduledPaymentStatus.EXECUTED);
                scheduledPayment.setExecutedAt(LocalDateTime.now());
            }
            log.info("[SCHEDULED_PAYMENT_EXECUTED] {} → paiement {} ({})", scheduledPayment.getId(), response.paymentId(), response.status());
        } catch (ProviderCommunicationException e) {
            // Prestataire indisponible : le versement sera retenté au prochain passage.
            log.warn("[SCHEDULED_PAYMENT_PROVIDER_ERROR] {} : {}", scheduledPayment.getId(), e.getMessage());
            scheduledPayment.setStatus(ScheduledPaymentStatus.SCHEDULED);
            scheduledPayment.setFailureReason("Prestataire temporairement indisponible");
        } catch (Exception e) {
            log.error("[SCHEDULED_PAYMENT_FAILED] {}", scheduledPayment.getId(), e);
            scheduledPayment.setStatus(ScheduledPaymentStatus.FAILED);
            scheduledPayment.setFailureReason(e.getMessage() != null ? e.getMessage() : "Erreur interne");
        } finally {
            scheduledPaymentRepository.save(scheduledPayment);
            CorrelationContext.clear();
        }
    }

    private ScheduledPayment find(String id) {
        return scheduledPaymentRepository.findById(id).orElseThrow(() -> new ScheduledPaymentNotFoundException(id));
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment scheduledPayment) {
        String paymentNumber = scheduledPayment.getPaymentId() == null ? null
                : paymentRepository.findById(scheduledPayment.getPaymentId()).map(Payment::getPaymentNumber).orElse(null);
        return toResponse(scheduledPayment, paymentNumber);
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment sp, String paymentNumber) {
        return new ScheduledPaymentResponse(sp.getId(), sp.getProvider(), sp.getProviderAccountId(), sp.getAmount(),
                sp.getPayerPhone(), sp.getScheduledAt(), sp.getExternalReference(), sp.getNote(), sp.getStatus(),
                sp.getPaymentId(), paymentNumber, sp.getFailureReason(), sp.getExecutedAt(), sp.getCreatedAt());
    }
}
