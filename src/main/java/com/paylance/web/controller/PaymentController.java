package com.paylance.web.controller;

import com.paylance.domain.entity.Payment;
import com.paylance.security.ApiKeyAuthenticationToken;
import com.paylance.security.apikey.ApiKeyPrincipal;
import com.paylance.service.PaymentContext;
import com.paylance.service.PaymentService;
import com.paylance.service.RefundService;
import com.paylance.web.dto.*;
import com.paylance.web.exception.PaymentNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API serveur à serveur des applications clientes ({@code Authorization: Bearer pl_live_...}).
 * Une application ne voit que ses propres paiements.
 */
@Tag(name = "Payments", description = "Initiation, consultation et remboursement des paiements (clé API)")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;
    private final RefundService refundService;

    @Operation(summary = "Initier un paiement (idempotent par en-tête Idempotency-Key)")
    @PostMapping
    public ResponseEntity<PaymentInitiateResponse> initiate(
            ApiKeyAuthenticationToken authentication,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentInitiateRequest request) {
        ApiKeyPrincipal key = authentication.getPrincipal();
        PaymentContext context = PaymentContext.forApiKey(key.organizationId(), key.applicationId(), key.environment());
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.initiate(context, idempotencyKey, request));
    }

    @Operation(summary = "Consulter le statut d'un paiement")
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> getStatus(ApiKeyAuthenticationToken authentication, @PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.toStatusResponse(ownPayment(authentication, paymentId)));
    }

    @Operation(summary = "Lister les remboursements d'un paiement")
    @GetMapping("/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> getRefunds(ApiKeyAuthenticationToken authentication, @PathVariable String paymentId) {
        ownPayment(authentication, paymentId);
        return ResponseEntity.ok(paymentService.getRefundsByPaymentId(paymentId));
    }

    @Operation(summary = "Demander le remboursement (total ou partiel) d'un paiement")
    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(
            ApiKeyAuthenticationToken authentication,
            @PathVariable String paymentId,
            @Valid @RequestBody RefundRequest request) {
        ownPayment(authentication, paymentId);
        RefundResponse response = refundService.refund(paymentId, request, "API:" + authentication.getPrincipal().applicationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Le tenant est déjà garanti par Hibernate ; on restreint en plus à l'application appelante. */
    private Payment ownPayment(ApiKeyAuthenticationToken authentication, String paymentId) {
        Payment payment = paymentService.getPayment(paymentId);
        if (!authentication.getPrincipal().applicationId().equals(payment.getApplicationId())) {
            throw new PaymentNotFoundException(paymentId);
        }
        return payment;
    }
}
