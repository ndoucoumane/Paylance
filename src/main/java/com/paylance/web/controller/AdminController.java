package com.paylance.web.controller;

import com.paylance.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Outils d'exploitation de la plateforme Paylance (hors données d'un tenant particulier). */
@Tag(name = "Admin", description = "Exploitation de la plateforme")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ReconciliationService reconciliationService;

    @Operation(summary = "Déclencher immédiatement la réconciliation (tous tenants)")
    @PostMapping("/reconciliation/run")
    public ResponseEntity<Void> runReconciliation() {
        reconciliationService.reconcilePendingPayments();
        return ResponseEntity.accepted().build();
    }
}
