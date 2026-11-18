package com.paylance.web.controller;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.service.ScheduledPaymentService;
import com.paylance.web.dto.ScheduledPaymentCreateRequest;
import com.paylance.web.dto.ScheduledPaymentResponse;
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

@Tag(name = "Dashboard — versements programmés", description = "Payouts programmés")
@RestController
@RequestMapping("/api/v1/dashboard/scheduled-payments")
@RequiredArgsConstructor
public class DashboardScheduledPaymentController {

    private final ScheduledPaymentService scheduledPaymentService;

    @Operation(summary = "Programmer un versement")
    @PostMapping
    public ResponseEntity<ScheduledPaymentResponse> create(@Valid @RequestBody ScheduledPaymentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduledPaymentService.create(request));
    }

    @Operation(summary = "Rechercher les versements programmés")
    @GetMapping
    public ResponseEntity<Page<ScheduledPaymentResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String paymentNumber,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) ScheduledPaymentStatus status,
            @RequestParam(required = false) PaymentProvider provider,
            @RequestParam(required = false) String providerAccountId,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Long maxAmount,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @PageableDefault(size = 20, sort = "scheduledAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(scheduledPaymentService.search(q, paymentNumber, phone, status, provider,
                providerAccountId, minAmount, maxAmount, from, to, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduledPaymentResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(scheduledPaymentService.getById(id));
    }

    @Operation(summary = "Annuler un versement programmé (tant qu'il est SCHEDULED)")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ScheduledPaymentResponse> cancel(@PathVariable String id) {
        return ResponseEntity.ok(scheduledPaymentService.cancel(id));
    }
}
