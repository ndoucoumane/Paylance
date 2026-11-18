package com.paylance.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.PaymentLog;
import com.paylance.domain.entity.PaymentStatusHistory;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.repository.PaymentLogRepository;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.PaymentStatusHistoryRepository;
import com.paylance.repository.spec.PaymentSpecifications;
import com.paylance.security.CurrentActor;
import com.paylance.service.PaymentContext;
import com.paylance.service.PaymentService;
import com.paylance.service.RefundService;
import com.paylance.tenant.TenantContext;
import com.paylance.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Paiements et remboursements de l'organisation sélectionnée ({@code X-Organization-Id}),
 * pour le dashboard. Toutes les lectures sont restreintes au tenant courant.
 */
@Tag(name = "Dashboard — paiements", description = "Consultation, liens de paiement et remboursements")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardPaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final PaymentRepository paymentRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    private static final java.util.Set<String> PAYMENT_SORT_FIELDS = java.util.Set.of("createdAt", "amount");

    @Operation(summary = "Créer un lien de paiement")
    @PostMapping("/payments/links")
    public ResponseEntity<PaymentInitiateResponse> createPaymentLink(@Valid @RequestBody PaymentLinkRequest request) {
        PaymentContext context = PaymentContext.forDashboard(TenantContext.requireOrganizationId(),
                request.applicationId(), CurrentActor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPaymentLink(context, request));
    }

    @Operation(summary = "Rechercher les paiements")
    @GetMapping("/payments")
    public ResponseEntity<Page<PaymentStatusResponse>> listPayments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        requireSortableBy(pageable, PAYMENT_SORT_FIELDS);
        var spec = PaymentSpecifications.filter(q, paymentNumber, phone, applicationId, status, provider, minAmount, maxAmount, from, to);
        return ResponseEntity.ok(paymentService.toStatusResponses(paymentRepository.findAll(spec, pageable)));
    }

    @Operation(summary = "Statistiques agrégées (statut, prestataire, application)")
    @GetMapping("/payments/stats")
    public ResponseEntity<PaymentStatsResponse> getStats(
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        List<PaymentStatsResponse.AggregateEntry> byStatus = paymentRepository.aggregateByStatus(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(a.getStatus().name(), a.getCount(), a.getTotalAmount())).toList();
        List<PaymentStatsResponse.AggregateEntry> byProvider = paymentRepository.aggregateByProvider(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(a.getProvider().name(), a.getCount(), a.getTotalAmount())).toList();
        List<PaymentStatsResponse.AggregateEntry> byApplication = paymentRepository.aggregateByApplication(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(Objects.requireNonNullElse(a.getApplicationId(), "MANUAL"),
                        a.getCount(), a.getTotalAmount())).toList();
        long totalCount = byStatus.stream().mapToLong(PaymentStatsResponse.AggregateEntry::count).sum();
        long totalAmount = byStatus.stream().mapToLong(PaymentStatsResponse.AggregateEntry::totalAmount).sum();
        return ResponseEntity.ok(new PaymentStatsResponse(totalCount, totalAmount, byStatus, byProvider, byApplication));
    }

    @Operation(summary = "Consulter un paiement (identifiant technique ou numéro PL-...)")
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> getPayment(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.toStatusResponse(paymentService.getPaymentByIdOrNumber(paymentId)));
    }

    @Operation(summary = "Trace technique complète d'un paiement")
    @GetMapping("/payments/{paymentId}/logs")
    public ResponseEntity<List<PaymentLogResponse>> getLogs(@PathVariable String paymentId) {
        Payment payment = paymentService.getPaymentByIdOrNumber(paymentId);
        return ResponseEntity.ok(paymentLogRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                .map(this::toLogResponse).toList());
    }

    @Operation(summary = "Historique des transitions de statut")
    @GetMapping("/payments/{paymentId}/history")
    public ResponseEntity<List<PaymentStatusHistoryResponse>> getHistory(@PathVariable String paymentId) {
        Payment payment = paymentService.getPaymentByIdOrNumber(paymentId);
        return ResponseEntity.ok(paymentStatusHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                .map(this::toHistoryResponse).toList());
    }

    @Operation(summary = "Remboursements d'un paiement")
    @GetMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> getPaymentRefunds(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getRefundsByPaymentId(paymentService.getPaymentByIdOrNumber(paymentId).getId()));
    }

    @Operation(summary = "Rembourser un paiement")
    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(@PathVariable String paymentId, @Valid @RequestBody RefundRequest request) {
        Payment payment = paymentService.getPaymentByIdOrNumber(paymentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(refundService.refund(payment.getId(), request,
                CurrentActor.displayName(), CurrentActor.userId()));
    }

    @Operation(summary = "Rechercher les remboursements")
    @GetMapping("/refunds")
    public ResponseEntity<Page<RefundResponse>> listRefunds(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(paymentService.searchRefunds(q, paymentNumber, phone, status, minAmount, maxAmount, from, to, pageable));
    }

    /** Tri limité à une liste blanche : jamais sur une colonne arbitraire choisie par le client. */
    private static void requireSortableBy(Pageable pageable, java.util.Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new IllegalArgumentException("Tri possible uniquement par : " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
        }
    }

    private PaymentLogResponse toLogResponse(PaymentLog log) {
        return new PaymentLogResponse(log.getId(), log.getLevel(), log.getEvent(), log.getMessage(),
                parsePayload(log.getPayload()), log.getCorrelationId(), log.getCreatedAt());
    }

    /** {@code payload} est stocké tel quel : JSON quand c'en est, sinon chaîne brute. */
    private JsonNode parsePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(rawPayload);
        }
    }

    private PaymentStatusHistoryResponse toHistoryResponse(PaymentStatusHistory history) {
        return new PaymentStatusHistoryResponse(history.getId(), history.getFromStatus(), history.getToStatus(),
                history.getReason(), history.getTriggeredBy(), history.getCreatedAt());
    }
}
