package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.repository.ScheduledPaymentRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paiements programmés créés depuis le dashboard admin : enregistrés au statut
 * {@code SCHEDULED}, puis exécutés (véritable versement sortant direct via {@link PaymentService#payout})
 * par le job planifié dès que {@code scheduledAt} est atteint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledPaymentService {

    private final ScheduledPaymentRepository scheduledPaymentRepository;
    private final PaymentService paymentService;
    private final com.paylance.repository.PaymentRepository paymentRepository;

    public ScheduledPaymentResponse create(ScheduledPaymentCreateRequest request) {
        ScheduledPayment scheduledPayment = ScheduledPayment.builder()
                .provider(request.provider())
                .platform(Platform.MANUAL)
                .amount(request.amount())
                .payerPhone(request.payerPhone())
                .scheduledAt(request.scheduledAt())
                .externalReference(request.externalReference())
                .note(request.note())
                .status(ScheduledPaymentStatus.SCHEDULED)
                .build();
        scheduledPayment = scheduledPaymentRepository.save(scheduledPayment);
        return toResponse(scheduledPayment);
    }

    public Page<ScheduledPaymentResponse> search(
            String query,
            String paymentNumber,
            String phone,
            ScheduledPaymentStatus status,
            com.paylance.domain.enums.PaymentProvider provider,
            Platform platform,
            Long minAmount,
            Long maxAmount,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    ) {
        java.util.Set<String> matchedPaymentIds = new java.util.HashSet<>();
        if (paymentNumber != null && !paymentNumber.isBlank()) {
            paymentRepository.findByPaymentNumber(paymentNumber.trim())
                    .ifPresent(p -> matchedPaymentIds.add(p.getId()));
        }
        if (query != null && !query.isBlank()) {
            paymentRepository.findByPaymentNumber(query.trim())
                    .ifPresent(p -> matchedPaymentIds.add(p.getId()));
        }

        org.springframework.data.jpa.domain.Specification<ScheduledPayment> spec =
                com.paylance.repository.spec.ScheduledPaymentSpecifications.filter(
                        query, phone, status, provider, platform, minAmount, maxAmount, from, to, matchedPaymentIds
                );

        Page<ScheduledPayment> page = scheduledPaymentRepository.findAll(spec, pageable);

        java.util.Set<String> paymentIds = page.getContent().stream()
                .map(ScheduledPayment::getPaymentId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> paymentNumberMap = paymentIds.isEmpty() ? Map.of() :
                paymentRepository.findAllById(paymentIds).stream()
                        .filter(p -> p.getPaymentNumber() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                com.paylance.domain.entity.Payment::getId,
                                com.paylance.domain.entity.Payment::getPaymentNumber,
                                (a, b) -> a
                        ));

        return page.map(sp -> toResponse(sp, sp.getPaymentId() != null ? paymentNumberMap.get(sp.getPaymentId()) : null));
    }

    public Page<ScheduledPaymentResponse> list(ScheduledPaymentStatus status, Pageable pageable) {
        return search(null, null, null, status, null, null, null, null, null, null, pageable);
    }

    public ScheduledPaymentResponse getById(String scheduledPaymentId) {
        ScheduledPayment scheduledPayment = scheduledPaymentRepository.findById(scheduledPaymentId)
                .orElseThrow(() -> new ScheduledPaymentNotFoundException(scheduledPaymentId));
        return toResponse(scheduledPayment);
    }

    public ScheduledPaymentResponse cancel(String scheduledPaymentId) {
        ScheduledPayment scheduledPayment = scheduledPaymentRepository.findById(scheduledPaymentId)
                .orElseThrow(() -> new ScheduledPaymentNotFoundException(scheduledPaymentId));
        if (scheduledPayment.getStatus() != ScheduledPaymentStatus.SCHEDULED) {
            throw new IllegalStateException("Impossible d'annuler un paiement programmé au statut " + scheduledPayment.getStatus());
        }
        scheduledPayment.setStatus(ScheduledPaymentStatus.CANCELLED);
        scheduledPayment = scheduledPaymentRepository.save(scheduledPayment);
        return toResponse(scheduledPayment);
    }

    @Scheduled(fixedDelayString = "${paylance.scheduled-payments.interval-ms:60000}")
    public void executeDuePayments() {
        long start = System.currentTimeMillis();
        List<ScheduledPayment> due =
        scheduledPaymentRepository.findDueForClaim(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        log.info("[SCHEDULED_PAYMENTS_JOB_START] {} paiement(s) programmé(s) à exécuter", due.size());
        due.forEach(this::executeOne);
        long duration = System.currentTimeMillis() - start;
        log.info("[SCHEDULED_PAYMENTS_JOB_COMPLETE] {} paiement(s) programmé(s) traité(s) en {}ms", due.size(), duration);
    }

    private void executeOne(ScheduledPayment scheduledPayment) {
        long start = System.currentTimeMillis();
        log.info("[SCHEDULED_PAYMENT_EXEC_START] Exécution paiement programmé {}: provider={}, amount={} XOF",
                scheduledPayment.getId(), scheduledPayment.getProvider(),
                java.math.BigDecimal.valueOf(scheduledPayment.getAmount(), 2).stripTrailingZeros().toPlainString());
        CorrelationContext.set(UUID.randomUUID().toString());
        try {
            scheduledPayment.setStatus(ScheduledPaymentStatus.EXECUTING);
            scheduledPaymentRepository.save(scheduledPayment);

            PaymentInitiateRequest request = new PaymentInitiateRequest(
                    "SCHEDULED-" + scheduledPayment.getId(),
                    scheduledPayment.getProvider(),
                    scheduledPayment.getAmount(),
                    "XOF",
                    scheduledPayment.getPayerPhone(),
                    null,
                    scheduledPayment.getExternalReference(),
                    null,
                    null,
                    scheduledPayment.getNote() != null
                            ? Map.of("note", scheduledPayment.getNote())
                            : null,
                    null,
                    null
            );

            PaymentInitiateResponse response =
                    paymentService.payout(scheduledPayment.getPlatform(), request);

            scheduledPayment.setPaymentId(response.paymentId());
            if (response.status() == PaymentStatus.SUCCESS) {
                scheduledPayment.setStatus(ScheduledPaymentStatus.EXECUTED);
                scheduledPayment.setExecutedAt(LocalDateTime.now());
            } else if (response.status() == PaymentStatus.FAILED) {
                scheduledPayment.setStatus(ScheduledPaymentStatus.FAILED);
                // Récupérer le vrai failureReason depuis le Payment créé par le payout
                String providerFailureReason = paymentRepository.findById(response.paymentId())
                        .map(com.paylance.domain.entity.Payment::getFailureReason)
                        .orElse(null);
                scheduledPayment.setFailureReason(
                        providerFailureReason != null && !providerFailureReason.isBlank()
                                ? providerFailureReason
                                : "Échec du virement provider");
            } else {
                scheduledPayment.setStatus(ScheduledPaymentStatus.EXECUTED);
                scheduledPayment.setExecutedAt(LocalDateTime.now());
            }

            long duration = System.currentTimeMillis() - start;
            log.info("[SCHEDULED_PAYMENT_EXEC_SUCCESS] Paiement programmé {} exécuté en {}ms (paymentId={}, status={})",
                    scheduledPayment.getId(), duration, response.paymentId(), response.status());

        } catch (ProviderCommunicationException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn(
                "[SCHEDULED_PAYMENT_EXEC_PROVIDER_ERROR] Erreur communication provider après {}ms pour paiement programmé {} : {}",
                duration,
                scheduledPayment.getId(),
                e.getMessage(),
                e
            );

            scheduledPayment.setStatus(ScheduledPaymentStatus.SCHEDULED);
            scheduledPayment.setFailureReason("Provider temporairement indisponible");

        } catch (IllegalStateException e) {
            long duration = System.currentTimeMillis() - start;
            log.error(
                "[SCHEDULED_PAYMENT_EXEC_INVALID_CONFIG] Configuration invalide après {}ms pour paiement programmé {} : {}",
                duration,
                scheduledPayment.getId(),
                e.getMessage(),
                e
            );

            scheduledPayment.setStatus(ScheduledPaymentStatus.FAILED);
            scheduledPayment.setFailureReason(e.getMessage());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error(
                "[SCHEDULED_PAYMENT_EXEC_UNEXPECTED_ERROR] Erreur inattendue après {}ms lors du paiement programmé {}",
                duration,
                scheduledPayment.getId(),
                e
            );

            scheduledPayment.setStatus(ScheduledPaymentStatus.FAILED);
            scheduledPayment.setFailureReason("Erreur interne");
        }
         finally {
            scheduledPaymentRepository.save(scheduledPayment);
            CorrelationContext.clear();
        }
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment scheduledPayment) {
        String paymentNumber = null;
        if (scheduledPayment.getPaymentId() != null) {
            paymentNumber = paymentRepository.findById(scheduledPayment.getPaymentId())
                    .map(com.paylance.domain.entity.Payment::getPaymentNumber)
                    .orElse(null);
        }
        return toResponse(scheduledPayment, paymentNumber);
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment scheduledPayment, String paymentNumber) {
        return new ScheduledPaymentResponse(
                scheduledPayment.getId(),
                scheduledPayment.getProvider(),
                scheduledPayment.getPlatform(),
                scheduledPayment.getAmount(),
                scheduledPayment.getPayerPhone(),
                scheduledPayment.getScheduledAt(),
                scheduledPayment.getExternalReference(),
                scheduledPayment.getNote(),
                scheduledPayment.getStatus(),
                scheduledPayment.getPaymentId(),
                paymentNumber,
                scheduledPayment.getFailureReason(),
                scheduledPayment.getExecutedAt(),
                scheduledPayment.getCreatedAt()
        );
    }
}
