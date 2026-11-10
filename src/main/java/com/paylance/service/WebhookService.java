package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.WebhookEvent;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.domain.enums.WebhookStatus;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.ProviderConnectionResolver;
import com.paylance.provider.WebhookParseResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.WebhookEventRepository;
import com.paylance.web.exception.InvalidWebhookSignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Traite les webhooks entrants des providers : vérification de signature, déduplication,
 * mise à jour du statut du paiement concerné. Chaque webhook reçu est audité dans
 * {@link WebhookEvent}, y compris ceux rejetés (signature invalide, doublon).
 *
 * <p>Volontairement <b>non</b> {@code @Transactional} : chaque {@code save(...)} committe
 * individuellement dans sa propre mini-transaction (comportement par défaut de
 * {@code SimpleJpaRepository}). Une méthode globale {@code @Transactional} annulerait
 * l'enregistrement d'audit (ex: {@code INVALID_SIGNATURE}) au moment même où une exception
 * est levée juste après pour signaler le rejet — perdant la trace qu'on cherche justement
 * à garder pour ce cas précis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderConnectionResolver connectionResolver;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentLogService paymentLogService;
    private final PaymentNumberGenerator paymentNumberGenerator;

    public void handle(PaymentProvider provider, String providerAccountId, String rawPayload, String signatureHeader) {
        long totalStart = System.currentTimeMillis();
        log.info("[WEBHOOK_HANDLE_START] Début traitement webhook pour provider={}", provider);

        PaymentProviderClient client = providerRegistry.resolve(provider);
        ProviderConnection connection = connectionResolver.forAccount(providerAccountId);
        if (connection.provider() != provider) {
            throw new InvalidWebhookSignatureException("Compte prestataire incompatible avec " + provider);
        }

        long tSig = System.currentTimeMillis();
        boolean signatureValid = client.verifyWebhookSignature(connection, rawPayload, signatureHeader);
        long sigDuration = System.currentTimeMillis() - tSig;
        log.info("[WEBHOOK_SIGNATURE_CHECK] Vérification signature {} en {}ms (valide={})", provider, sigDuration, signatureValid);

        String correlationId = CorrelationContext.current();

        if (!signatureValid) {
            webhookEventRepository.save(WebhookEvent.builder()
                    .provider(provider)
                    .signature(signatureHeader)
                    .signatureValid(false)
                    .rawPayload(rawPayload)
                    .status(WebhookStatus.INVALID_SIGNATURE)
                    .correlationId(correlationId)
                    .build());
            long totalInvalid = System.currentTimeMillis() - totalStart;
            log.warn("[WEBHOOK_INVALID_SIGNATURE] Signature webhook invalide pour le provider {} (rejeté après {}ms)", provider, totalInvalid);
            throw new InvalidWebhookSignatureException("Signature webhook invalide pour " + provider);
        }

        long tParse = System.currentTimeMillis();
        WebhookParseResult parsed = client.parseWebhookEvent(rawPayload);
        long parseDuration = System.currentTimeMillis() - tParse;
        log.info("[WEBHOOK_PARSED] Payload {} parsé en {}ms (eventId={}, txId={}, status={})",
                provider, parseDuration, parsed.providerEventId(), parsed.providerTransactionId(), parsed.status());

        if (parsed.providerEventId() != null) {
            long tDedup = System.currentTimeMillis();
            boolean exists = webhookEventRepository.existsByProviderAndProviderEventId(provider, parsed.providerEventId());
            long dedupDuration = System.currentTimeMillis() - tDedup;
            if (exists) {
                webhookEventRepository.save(WebhookEvent.builder()
                        .provider(provider)
                        .providerEventId(parsed.providerEventId())
                        .signature(signatureHeader)
                        .signatureValid(true)
                        .rawPayload(rawPayload)
                        .status(WebhookStatus.IGNORED_DUPLICATE)
                        .correlationId(correlationId)
                        .build());
                long totalDedup = System.currentTimeMillis() - totalStart;
                log.info("[WEBHOOK_DUPLICATE_IGNORED] Webhook {} déjà traité pour le provider {}, ignoré en {}ms (idempotence)",
                        parsed.providerEventId(), provider, totalDedup);
                return;
            }
        }

        long tEventSave = System.currentTimeMillis();
        WebhookEvent event = WebhookEvent.builder()
                .provider(provider)
                .providerEventId(parsed.providerEventId())
                .signature(signatureHeader)
                .signatureValid(true)
                .rawPayload(rawPayload)
                .status(WebhookStatus.RECEIVED)
                .correlationId(correlationId)
                .build();
        event = webhookEventRepository.save(event);
        long eventSaveDuration = System.currentTimeMillis() - tEventSave;
        log.info("[WEBHOOK_EVENT_SAVED] WebhookEvent initial sauvegardé en {}ms (eventId={})", eventSaveDuration, event.getId());

        if (parsed.providerTransactionId() == null || parsed.providerTransactionId().isBlank()) {
            // Un providerTransactionId absent (ex: payload de test générique envoyé depuis le
            // dashboard provider) ferait matcher TOUS les paiements sans providerTransactionId
            // renseigné (findByProviderTransactionId(null) génère "IS NULL" côté SQL, et rien ne
            // garantit l'unicité de cette colonne) — NonUniqueResultException sinon.
            event.setStatus(WebhookStatus.FAILED);
            event.setProcessingError("Webhook sans providerTransactionId exploitable (ex: événement de test)");
            webhookEventRepository.save(event);
            log.warn("[WEBHOOK_MISSING_TXID] Webhook {} reçu sans providerTransactionId exploitable — ignoré", provider);
            return;
        }

        long tFindPay = System.currentTimeMillis();
        Optional<Payment> paymentOpt = paymentRepository.findByProviderTransactionId(parsed.providerTransactionId());
        long findPayDuration = System.currentTimeMillis() - tFindPay;
        Payment payment;
        if (paymentOpt.isPresent()) {
            payment = paymentOpt.get();
            log.info("[WEBHOOK_PAYMENT_FOUND] Paiement {} trouvé en {}ms pour providerTransactionId={}",
                    payment.getId(), findPayDuration, parsed.providerTransactionId());
        } else if (parsed.unattributed() != null) {
            log.info("[WEBHOOK_PAYMENT_UNATTRIBUTED] Paiement non attribué détecté pour {} (txId={})",
                    provider, parsed.providerTransactionId());
            payment = createUnattributedPayment(provider, parsed);
        } else {
            event.setStatus(WebhookStatus.FAILED);
            event.setProcessingError("Aucun paiement trouvé pour providerTransactionId=" + parsed.providerTransactionId());
            webhookEventRepository.save(event);
            long totalNotFound = System.currentTimeMillis() - totalStart;
            log.error("[WEBHOOK_PAYMENT_NOT_FOUND] Webhook {} reçu pour une transaction provider inconnue en {}ms : {}",
                    provider, totalNotFound, parsed.providerTransactionId());
            return;
        }

        event.setPayment(payment);
        paymentLogService.info(payment, "WEBHOOK_RECEIVED", "Webhook reçu de " + provider, rawPayload);

        // Met à jour le providerTransactionId si le webhook fournit un vrai transactionId
        // côté provider (différent de l'ID Paylance utilisé comme référence à l'initiation).
        // Indispensable pour Orange Money : le QR code ne renvoie aucun transactionId à la
        // création, on utilise donc payment.getId() comme reference/providerTransactionId
        // initial. Le vrai transactionId Orange n'arrive que dans le webhook de confirmation
        // — sans cette mise à jour, le remboursement échoue (404 Entity not found).
        if (parsed.providerTransactionId() != null
                && !parsed.providerTransactionId().isBlank()
                && !parsed.providerTransactionId().equals(payment.getProviderTransactionId())) {
            log.info("[WEBHOOK_UPDATE_PROVIDER_TXID] Mise à jour providerTransactionId pour le paiement {} : {} → {}",
                    payment.getId(), payment.getProviderTransactionId(), parsed.providerTransactionId());
            payment.setProviderTransactionId(parsed.providerTransactionId());
            paymentRepository.save(payment);
        }

        long tTransition = System.currentTimeMillis();
        payment = statusTransitionService.transitionStatus(payment, parsed.status(), "Webhook " + provider, TriggeredBy.WEBHOOK);
        long transitionDuration = System.currentTimeMillis() - tTransition;
        log.info("[WEBHOOK_TRANSITION_COMPLETE] Transition statut webhook exécutée en {}ms (status={})",
                transitionDuration, parsed.status());

        if (parsed.status() == PaymentStatus.FAILED && parsed.failureReason() != null) {
            payment.setFailureReason(parsed.failureReason());
            paymentRepository.save(payment);
        }
        if (provider == PaymentProvider.ORANGE_MONEY && parsed.status() == PaymentStatus.SUCCESS) {
            enrichActualPayerPhone(client, connection, payment);
        }

        event.setStatus(WebhookStatus.PROCESSED);
        event.setProcessedAt(LocalDateTime.now());
        webhookEventRepository.save(event);

        long totalDuration = System.currentTimeMillis() - totalStart;
        log.info("[WEBHOOK_HANDLE_COMPLETE] Traitement webhook {} terminé avec succès en {}ms pour paymentId={}",
                provider, totalDuration, payment.getId());
    }

    /**
     * Le webhook Orange Money (notification marchand) ne porte que {@code reference/transactionId
     * /status/detail} — le numéro ayant effectivement payé ({@code subscriberMsisdn}) n'est
     * disponible que via l'endpoint de statut ({@link PaymentProviderClient#checkStatus}). Best
     * effort : un échec ici ne doit pas remettre en cause le traitement du webhook, déjà terminé.
     */
    private void enrichActualPayerPhone(PaymentProviderClient client, ProviderConnection connection, Payment payment) {
        long start = System.currentTimeMillis();
        try {
            String actual = client.checkStatus(connection, payment).actualPayerPhone();
            long duration = System.currentTimeMillis() - start;
            if (actual != null && !actual.isBlank() && !actual.equals(payment.getActualPayerPhone())) {
                log.info("[WEBHOOK_ENRICH_PHONE_SUCCESS] Numéro payeur réel enrichi en {}ms pour le paiement {} : {}",
                        duration, payment.getId(), actual);
                payment.setActualPayerPhone(actual);
                paymentRepository.save(payment);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[WEBHOOK_ENRICH_PHONE_FAILED] Impossible de récupérer le numéro payeur réel pour le paiement {} après {}ms: {}",
                    payment.getId(), duration, e.getMessage());
        }
    }

    /**
     * Crée la transaction pour un paiement reçu directement chez le provider sans initiation
     * Paylance préalable (ex: lien de paiement Wave partagé manuellement à un client). Statut
     * laissé à {@link PaymentStatus#PENDING} : la transition vers le statut réel de l'événement
     * (juste après l'appel) passe par {@link PaymentStatusTransitionService}, pour garantir un
     * historique de transition écrit comme pour n'importe quel autre paiement.
     */
    private Payment createUnattributedPayment(PaymentProvider provider, WebhookParseResult parsed) {
        WebhookParseResult.UnattributedPayment unattributed = parsed.unattributed();
        Payment payment = Payment.builder()
                .paymentNumber(paymentNumberGenerator.generate())
                .idempotencyKey(provider + "-MANUAL-" + parsed.providerTransactionId())
                .correlationId(CorrelationContext.current())
                .platform(Platform.MANUAL)
                .provider(provider)
                .providerTransactionId(parsed.providerTransactionId())
                .amount(unattributed.amount())
                .currency(unattributed.currency() != null ? unattributed.currency() : "XOF")
                .payerPhone(unattributed.payerPhone())
                .status(PaymentStatus.PENDING)
                .build();
        payment = paymentRepository.save(payment);
        paymentLogService.info(payment, "PAYMENT_RECEIVED_UNATTRIBUTED",
                "Paiement reçu directement sur le compte " + provider + ", hors initiation Paylance (ex: lien de paiement) — à rattacher");
        log.info("[WEBHOOK_UNATTRIBUTED_CREATED] Paiement {} créé hors initiation Paylance pour {} (providerTransactionId={})",
                payment.getId(), provider, parsed.providerTransactionId());
        return payment;
    }
}
