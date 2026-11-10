package com.paylance.web.controller;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Réception des webhooks Wave / Orange Money. Non protégés par API key (endpoints publics
 * côté provider) : l'authenticité est garantie par la vérification de signature HMAC
 * effectuée dans {@link WebhookService} sur le corps brut de la requête.
 */
@Slf4j
@Tag(name = "Webhooks", description = "Réception des notifications provider (Wave, Orange Money)")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @Operation(summary = "Webhook Wave (checkout session)")
    @PostMapping("/wave/{providerAccountId}")
    public ResponseEntity<Void> wave(
            @PathVariable String providerAccountId,
            @RequestBody String rawPayload,
            @RequestHeader(value = "Wave-Signature", required = false) String signature) {
        long start = System.currentTimeMillis();
        log.info("[WEBHOOK_HTTP_RECEIVED] Webhook Wave reçu (taille payload: {} octets, signature présente: {})",
                rawPayload != null ? rawPayload.length() : 0, signature != null);
        webhookService.handle(PaymentProvider.WAVE, providerAccountId, rawPayload, signature);
        long duration = System.currentTimeMillis() - start;
        log.info("[WEBHOOK_HTTP_COMPLETE] Webhook Wave traité en {}ms", duration);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Webhook Orange Money (notification de paiement, Sonatel eWallet)")
    @PostMapping("/orange-money/{providerAccountId}")
    public ResponseEntity<Void> orangeMoney(
            @PathVariable String providerAccountId,
            @RequestBody String rawPayload,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long start = System.currentTimeMillis();
        log.info("[WEBHOOK_HTTP_RECEIVED] Webhook Orange Money reçu (taille payload: {} octets, auth présente: {})",
                rawPayload != null ? rawPayload.length() : 0, authorization != null);
        webhookService.handle(PaymentProvider.ORANGE_MONEY, providerAccountId, rawPayload, authorization);
        long duration = System.currentTimeMillis() - start;
        log.info("[WEBHOOK_HTTP_COMPLETE] Webhook Orange Money traité en {}ms", duration);
        return ResponseEntity.ok().build();
    }
}
