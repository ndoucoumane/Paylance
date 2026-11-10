package com.paylance.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.PaymentLog;
import com.paylance.domain.entity.PaymentStatusHistory;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.repository.PaymentLogRepository;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.PaymentStatusHistoryRepository;
import com.paylance.repository.spec.PaymentSpecifications;
import com.paylance.service.PaymentService;
import com.paylance.service.ReconciliationService;
import com.paylance.service.ScheduledPaymentService;
import com.paylance.web.dto.AdminPaymentLinkRequest;
import com.paylance.web.dto.PaymentInitiateResponse;
import com.paylance.web.dto.PaymentLogResponse;
import com.paylance.web.dto.PaymentStatsResponse;
import com.paylance.web.dto.PaymentStatusHistoryResponse;
import com.paylance.web.dto.PaymentStatusResponse;
import com.paylance.web.dto.RefundRequest;
import com.paylance.web.dto.RefundResponse;
import com.paylance.web.dto.ScheduledPaymentCreateRequest;
import com.paylance.web.dto.ScheduledPaymentResponse;
import com.paylance.web.dto.WalletBalanceResponse;
import com.paylance.provider.wave.WaveClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Endpoints internes (support/ops) : consultation cross-plateforme et déclenchement manuel
 * de la reconciliation. Protégés par {@code X-Admin-Key} (voir {@link com.paylance.security.AdminApiKeyFilter}),
 * distinct des clés API par plateforme.
 */
@Tag(name = "Admin", description = "Consultation interne et outils d'exploitation")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PaymentService paymentService;
    private final ScheduledPaymentService scheduledPaymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentLogRepository paymentLogRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final ReconciliationService reconciliationService;
    private final WaveClient waveClient;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Créer un lien de paiement (paiement demandé manuellement par un agent, hors flux plateforme)")
    @PostMapping("/payments/link")
    public ResponseEntity<PaymentInitiateResponse> createPaymentLink(@Valid @RequestBody AdminPaymentLinkRequest request) {
        PaymentInitiateResponse response = paymentService.createPaymentLink(
                request.platform(), request.provider(), request.amount(), request.currency(),
                request.payerPhone(), request.payerName(), request.expiresInMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Liste et recherche paginée des paiements (filtrable par query/numéro, téléphone, plateforme, statut, provider, montant, période)")
    @GetMapping("/payments")
    public ResponseEntity<Page<PaymentStatusResponse>> listPayments(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Platform platform,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        String searchQuery = query != null && !query.isBlank() ? query : q;
        Page<PaymentStatusResponse> page = paymentRepository
                .findAll(PaymentSpecifications.filter(searchQuery, paymentNumber, phone, platform, status, provider, minAmount, maxAmount, from, to), pageable)
                .map(paymentService::toStatusResponse);
        return ResponseEntity.ok(page);
    }

    @Operation(summary = "Statistiques agrégées des paiements (comptage et volumes par statut, provider, plateforme)")
    @GetMapping("/payments/stats")
    public ResponseEntity<PaymentStatsResponse> getStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<PaymentStatsResponse.AggregateEntry> byStatus = paymentRepository.aggregateByStatus(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(a.getStatus().name(), a.getCount(), a.getTotalAmount()))
                .toList();
        List<PaymentStatsResponse.AggregateEntry> byProvider = paymentRepository.aggregateByProvider(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(a.getProvider().name(), a.getCount(), a.getTotalAmount()))
                .toList();
        List<PaymentStatsResponse.AggregateEntry> byPlatform = paymentRepository.aggregateByPlatform(from, to).stream()
                .map(a -> new PaymentStatsResponse.AggregateEntry(a.getPlatform().name(), a.getCount(), a.getTotalAmount()))
                .toList();

        long totalCount = byStatus.stream().mapToLong(PaymentStatsResponse.AggregateEntry::count).sum();
        long totalAmount = byStatus.stream().mapToLong(PaymentStatsResponse.AggregateEntry::totalAmount).sum();

        return ResponseEntity.ok(new PaymentStatsResponse(totalCount, totalAmount, byStatus, byProvider, byPlatform));
    }

    @Operation(summary = "Consulter un paiement (toutes plateformes) — accepte l'id technique ou le paymentNumber")
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> getPayment(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getStatus(resolvePaymentId(paymentId)));
    }

    @Operation(summary = "Trace complète d'un paiement (logs techniques)")
    @GetMapping("/payments/{paymentId}/logs")
    public ResponseEntity<List<PaymentLogResponse>> getLogs(@PathVariable String paymentId) {
        List<PaymentLogResponse> logs = paymentLogRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(this::toLogResponse)
                .toList();
        return ResponseEntity.ok(logs);
    }

    @Operation(summary = "Historique des transitions de statut d'un paiement")
    @GetMapping("/payments/{paymentId}/history")
    public ResponseEntity<List<PaymentStatusHistoryResponse>> getHistory(@PathVariable String paymentId) {
        List<PaymentStatusHistoryResponse> history = paymentStatusHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(this::toHistoryResponse)
                .toList();
        return ResponseEntity.ok(history);
    }

    @Operation(summary = "Liste des remboursements d'un paiement (accepte l'id technique ou le paymentNumber)")
    @GetMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> getPaymentRefunds(@PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getRefundsByPaymentId(resolvePaymentId(paymentId)));
    }

    @Operation(summary = "Liste paginée et recherche de tous les remboursements (filtrable par query, numéro, téléphone, statut, montant, dates)")
    @GetMapping("/refunds")
    public ResponseEntity<Page<RefundResponse>> listRefunds(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        String searchQuery = query != null && !query.isBlank() ? query : q;
        return ResponseEntity.ok(paymentService.searchRefunds(searchQuery, paymentNumber, phone, status, minAmount, maxAmount, from, to, pageable));
    }

    @Operation(summary = "Rembourser un paiement (toutes plateformes, sans restriction d'appartenance — usage admin/dashboard)")
    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable String paymentId,
            @Valid @RequestBody RefundRequest request) {
        RefundResponse response = paymentService.refund(resolvePaymentId(paymentId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Accepte soit l'id technique (UUID), soit le paymentNumber lisible affiché partout ailleurs
     * dans le dashboard (ex: PL-260720-000001-6) — un opérateur n'a normalement accès qu'à ce
     * dernier, jamais à l'id technique, lorsqu'il saisit un paiement manuellement.
     */
    private String resolvePaymentId(String rawIdentifier) {
        if (paymentRepository.existsById(rawIdentifier)) {
            return rawIdentifier;
        }
        return paymentRepository.findByPaymentNumber(rawIdentifier)
                .map(Payment::getId)
                .orElse(rawIdentifier);
    }
    @Operation(summary = "Déclencher manuellement le job de reconciliation")
    @PostMapping("/reconciliation/run")
    public ResponseEntity<Void> runReconciliation() {
        reconciliationService.reconcilePendingPayments();
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Créer un paiement programmé (exécuté automatiquement par le job à scheduledAt)")
    @PostMapping("/scheduled-payments")
    public ResponseEntity<ScheduledPaymentResponse> createScheduledPayment(@Valid @RequestBody ScheduledPaymentCreateRequest request) {
        ScheduledPaymentResponse response = scheduledPaymentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Liste paginée et recherche des paiements programmés (filtrable par query, numéro, téléphone, statut, provider, montant, dates)")
    @GetMapping("/scheduled-payments")
    public ResponseEntity<Page<ScheduledPaymentResponse>> listScheduledPayments(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) ScheduledPaymentStatus status,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) Platform platform,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "scheduledAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        String searchQuery = query != null && !query.isBlank() ? query : q;
        return ResponseEntity.ok(scheduledPaymentService.search(searchQuery, paymentNumber, phone, status, provider, platform, minAmount, maxAmount, from, to, pageable));
    }

    @Operation(summary = "Consulter un paiement programmé par son ID")
    @GetMapping("/scheduled-payments/{scheduledPaymentId}")
    public ResponseEntity<ScheduledPaymentResponse> getScheduledPayment(@PathVariable String scheduledPaymentId) {
        return ResponseEntity.ok(scheduledPaymentService.getById(scheduledPaymentId));
    }

    @Operation(summary = "Annuler un paiement programmé (tant qu'il est au statut SCHEDULED)")
    @PostMapping("/scheduled-payments/{scheduledPaymentId}/cancel")
    public ResponseEntity<ScheduledPaymentResponse> cancelScheduledPayment(@PathVariable String scheduledPaymentId) {
        return ResponseEntity.ok(scheduledPaymentService.cancel(scheduledPaymentId));
    }

    @Operation(summary = "Consulter le solde du wallet WAVE")
    @GetMapping("/wallet/balance")
    public ResponseEntity<WalletBalanceResponse> getWalletBalance() {
        var waveBalance = waveClient.getBalance();
        return ResponseEntity.ok(new WalletBalanceResponse(
                waveBalance.amount(),
                waveBalance.currency(),
                PaymentProvider.WAVE
        ));
    }

    private PaymentLogResponse toLogResponse(PaymentLog log) {
        return new PaymentLogResponse(log.getId(), log.getLevel(), log.getEvent(), log.getMessage(),
                parsePayload(log.getPayload()), log.getCorrelationId(), log.getCreatedAt());
    }

    /**
     * {@code payload} est stocké en base tel quel (TEXT) : parfois du JSON (réponse provider,
     * requête d'initiation...), parfois du texte brut (ex: raison d'un remboursement). On tente
     * un parsing JSON pour un rendu imbriqué propre côté API ; en cas d'échec, on le garde tel
     * quel comme simple chaîne — aucune donnée perdue dans les deux cas.
     */
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
