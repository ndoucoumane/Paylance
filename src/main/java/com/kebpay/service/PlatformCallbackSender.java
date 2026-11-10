package com.kebpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kebpay.domain.entity.Payment;
import com.kebpay.repository.PaymentRepository;
import com.kebpay.web.dto.CallbackPayload;
import com.kebpay.web.exception.PaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Notifie la plateforme cliente du résultat final d'un paiement (webhook sortant vers son
 * {@code callbackUrl}), avec retry + backoff exponentiel. Chaque tentative est journalisée.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformCallbackSender {

    private static final int MAX_ATTEMPTS = 5;

    private final PaymentRepository paymentRepository;
    private final PaymentLogService paymentLogService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // Pas de @Transactional ici : ne pas tenir une connexion JDBC ouverte pendant l'appel
    // réseau sortant (potentiellement lent) vers la plateforme cliente. Chaque sauvegarde
    // committe individuellement dans sa propre mini-transaction.
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 60000)
    )
    public void send(String paymentId) {
        long start = System.currentTimeMillis();
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getCallbackUrl() == null || payment.getCallbackUrl().isBlank()) {
            log.info("[CALLBACK_SKIPPED] Aucune callbackUrl configurée pour paymentId={}", paymentId);
            paymentLogService.warn(payment, "CALLBACK_SKIPPED", "Aucune callbackUrl configurée");
            return;
        }
        if (Boolean.TRUE.equals(payment.getCallbackSuccess())) {
            log.info("[CALLBACK_ALREADY_SUCCESS] Callback déjà marqué avec succès pour paymentId={}", paymentId);
            return; // déjà notifié avec succès, rien à refaire
        }

        CallbackPayload payload = new CallbackPayload(
                payment.getId(), payment.getCorrelationId(), payment.getStatus(),
                payment.getExternalReference(), payment.getAmount(), payment.getCurrency(), payment.getFailureReason());

        payment.setCallbackAttempts(payment.getCallbackAttempts() + 1);
        payment.setLastCallbackAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        log.info("[CALLBACK_SEND_START] Envoi callback HTTP vers {} (tentative {}/{}) pour paymentId={}",
                payment.getCallbackUrl(), payment.getCallbackAttempts(), MAX_ATTEMPTS, paymentId);

        long httpStart = System.currentTimeMillis();
        try {
            webClient.post()
                    .uri(payment.getCallbackUrl())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .block();

            long httpDuration = System.currentTimeMillis() - httpStart;
            long totalDuration = System.currentTimeMillis() - start;

            payment.setCallbackSuccess(true);
            paymentRepository.save(payment);

            log.info("[CALLBACK_SEND_SUCCESS] Callback HTTP délivré avec succès en {}ms (tentative {}/{}) pour paymentId={}",
                    httpDuration, payment.getCallbackAttempts(), MAX_ATTEMPTS, paymentId);

            paymentLogService.info(payment, "CALLBACK_SENT",
                    "Callback envoyé avec succès (tentative " + payment.getCallbackAttempts() + ")", writeJson(payload), httpDuration);
        } catch (Exception e) {
            long httpDuration = System.currentTimeMillis() - httpStart;
            paymentRepository.save(payment);

            log.error("[CALLBACK_SEND_FAILED] Échec envoi callback HTTP après {}ms (tentative {}/{}): {}",
                    httpDuration, payment.getCallbackAttempts(), MAX_ATTEMPTS, e.getMessage());

            paymentLogService.error(payment, "CALLBACK_FAILED",
                    "Échec callback (tentative " + payment.getCallbackAttempts() + "/" + MAX_ATTEMPTS + ") : " + e.getMessage(),
                    writeJson(payload), httpDuration);
            throw e;
        }
    }

    @Recover
    public void recover(Exception e, String paymentId) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            log.error("Callback définitivement échoué pour le paiement {} après {} tentatives", paymentId, MAX_ATTEMPTS, e);
            paymentLogService.error(payment, "CALLBACK_EXHAUSTED",
                    "Toutes les tentatives de callback ont échoué : " + e.getMessage(), null);
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
