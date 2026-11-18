package com.paylance.web.controller;

import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.security.CurrentActor;
import com.paylance.service.ProviderAccountService;
import com.paylance.web.dto.onboarding.ProviderAccountCreateRequest;
import com.paylance.web.dto.onboarding.ProviderAccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Comptes marchands Wave / Orange Money de l'organisation sélectionnée ({@code X-Organization-Id}).
 * Lecture : tout membre. Écriture (création, test, désactivation) : OWNER / ADMIN (SecurityConfig).
 * Les identifiants ne sont jamais renvoyés en clair.
 */
@Slf4j
@Tag(name = "Dashboard — comptes prestataires", description = "Comptes Wave et Orange Money du marchand")
@RestController
@RequestMapping("/api/v1/dashboard/provider-accounts")
@RequiredArgsConstructor
public class ProviderAccountController {

    private final ProviderAccountService providerAccountService;

    @Value("${paylance.public-base-url}")
    private String publicBaseUrl;

    @Operation(summary = "Comptes de l'organisation (hors comptes désactivés)")
    @GetMapping
    public List<ProviderAccountResponse> list() {
        return providerAccountService.list().stream()
                .filter(account -> account.getStatus() != ProviderAccountStatus.DISABLED)
                .map(this::toResponse)
                .toList();
    }

    @Operation(summary = "Déclarer un compte ; il reste PENDING_VERIFICATION jusqu'à un test réussi")
    @PostMapping
    public ResponseEntity<ProviderAccountResponse> create(@Valid @RequestBody ProviderAccountCreateRequest request) {
        ProviderAccount account = providerAccountService.create(request.provider(), request.label().trim(),
                request.environment(), request.credentials(), request.webhookSecret());
        audit("CREATED", account);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(account));
    }

    @Operation(summary = "Tester la connexion chez l'opérateur ; un succès active le compte")
    @PostMapping("/{id}/test")
    public ProviderAccountResponse test(@PathVariable String id) {
        ProviderAccount account = providerAccountService.testConnection(id);
        audit("TESTED", account);
        return toResponse(account);
    }

    @Operation(summary = "Désactiver le compte : plus aucun paiement ni remboursement possible")
    @PostMapping("/{id}/disable")
    public ProviderAccountResponse disable(@PathVariable String id) {
        ProviderAccount account = providerAccountService.disable(id);
        audit("DISABLED", account);
        return toResponse(account);
    }

    private ProviderAccountResponse toResponse(ProviderAccount account) {
        return new ProviderAccountResponse(account.getId(), account.getProvider(), account.getLabel(),
                account.getEnvironment(), account.getStatus(), providerAccountService.maskedCredentials(account),
                providerAccountService.hasWebhookSecret(account), webhookUrl(account),
                instant(account.getLastVerifiedAt()), account.getLastVerificationMessage(),
                instant(account.getCreatedAt()), instant(account.getUpdatedAt()));
    }

    /** Chemins servis par WebhookController. */
    private String webhookUrl(ProviderAccount account) {
        String segment = account.getProvider() == PaymentProvider.WAVE ? "wave" : "orange-money";
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + "/api/v1/webhooks/" + segment + "/" + account.getId();
    }

    private static void audit(String action, ProviderAccount account) {
        log.info("[AUDIT] PROVIDER_ACCOUNT_{} account={} provider={} environment={} status={} by={}",
                action, account.getId(), account.getProvider(), account.getEnvironment(), account.getStatus(),
                CurrentActor.userId());
    }

    /** La JVM tourne en UTC (PaylanceApplication) : les LocalDateTime stockés sont de l'UTC. */
    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
