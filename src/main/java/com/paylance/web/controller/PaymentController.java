package com.paylance.web.controller;

import com.paylance.security.PlatformPrincipal;
import com.paylance.service.PaymentService;
import com.paylance.web.dto.*;
import com.paylance.web.exception.PaymentNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API exposée aux plateformes clientes (Afrisurgelée, Kssid, Fleet360...) pour initier un paiement,
 * consulter son statut et demander un remboursement. Authentification via API key
 * (headers {@code X-Platform} / {@code X-API-Key}, voir {@link com.paylance.security.ApiKeyAuthFilter}).
 */
@Slf4j
@Tag(name = "Payments", description = "Initiation, consultation et remboursement des paiements")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "Initier un paiement (idempotent par idempotencyKey)")
    @PostMapping
    public ResponseEntity<PaymentInitiateResponse> initiate(
            Authentication authentication,
            @Valid @RequestBody PaymentInitiateRequest request) {
        long start = System.currentTimeMillis();
        var platform = principal(authentication).platform();
        log.info("[HTTP_PAYMENT_INIT_START] Requête POST /api/v1/payments reçue de {}", platform);
        PaymentInitiateResponse response = paymentService.initiate(platform, request);
        long duration = System.currentTimeMillis() - start;
        log.info("[HTTP_PAYMENT_INIT_COMPLETE] Requête POST /api/v1/payments terminée en {}ms pour paymentId={}",
                duration, response.paymentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Consulter le statut d'un paiement")
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> getStatus(
            Authentication authentication,
            @PathVariable String paymentId) {
        long start = System.currentTimeMillis();
        var platform = principal(authentication).platform();
        log.info("[HTTP_GET_STATUS_START] Requête GET /api/v1/payments/{} reçue de {}", paymentId, platform);
        PaymentStatusResponse response = paymentService.getStatus(paymentId);
        if (response.platform() != platform) {
            log.warn("[HTTP_GET_STATUS_FORBIDDEN] Plateforme {} a tenté d'accéder au paiement {} appartenant à {}",
                    platform, paymentId, response.platform());
            throw new PaymentNotFoundException(paymentId);
        }
        long duration = System.currentTimeMillis() - start;
        log.info("[HTTP_GET_STATUS_COMPLETE] Requête GET /api/v1/payments/{} terminée en {}ms (status={})",
                paymentId, duration, response.status());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Consulter la liste des remboursements d'un paiement")
    @GetMapping("/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> getRefunds(
            Authentication authentication,
            @PathVariable String paymentId) {
        long start = System.currentTimeMillis();
        var platform = principal(authentication).platform();
        PaymentStatusResponse payment = paymentService.getStatus(paymentId);
        if (payment.platform() != platform) {
            throw new PaymentNotFoundException(paymentId);
        }
        List<RefundResponse> refunds = paymentService.getRefundsByPaymentId(paymentId);
        long duration = System.currentTimeMillis() - start;
        log.info("[HTTP_GET_REFUNDS_COMPLETE] Requête GET /api/v1/payments/{}/refunds terminée en {}ms (nb={})",
                paymentId, duration, refunds.size());
        return ResponseEntity.ok(refunds);
    }
    @Operation(summary = "Demander le remboursement (total ou partiel) d'un paiement via l'admin")
    @PostMapping("/admin/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(
            Authentication authentication,
            @PathVariable String paymentId,
            @Valid @RequestBody RefundRequest request) {
        long start = System.currentTimeMillis();
        var platform = principal(authentication).platform();
        log.info("[HTTP_REFUND_START] Requête POST /api/v1/payments/admin/{}/refunds reçue de {}", paymentId, platform);
        // vérifie l'appartenance avant de déclencher le remboursement
        PaymentStatusResponse payment = paymentService.getStatus(paymentId);
        if (payment.platform() != platform) {
            throw new PaymentNotFoundException(paymentId);
        }
        RefundResponse response = paymentService.refund(paymentId, request);
        long duration = System.currentTimeMillis() - start;
        log.info("[HTTP_REFUND_COMPLETE] Requête POST /api/v1/payments/admin/{}/refunds terminée en {}ms (refundId={})",
                paymentId, duration, response.refundId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private PlatformPrincipal principal(Authentication authentication) {
        return (PlatformPrincipal) authentication.getPrincipal();
    }
}