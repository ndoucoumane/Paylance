package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.entity.WebhookEvent;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.domain.enums.WebhookStatus;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.WebhookParseResult;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.ProviderAccountRepository;
import com.paylance.repository.WebhookEventRepository;
import com.paylance.tenant.TenantContext;
import com.paylance.web.exception.InvalidWebhookSignatureException;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Webhooks entrants, adressés à un compte prestataire précis
 * ({@code /api/v1/webhooks/{provider}/{providerAccountId}}).
 *
 * <ol>
 *   <li>Le compte est résolu en mode système (le tenant n'est pas encore connu) ;</li>
 *   <li>tout le reste s'exécute dans le tenant de ce compte : signature vérifiée avec le secret
 *   de CE compte, et seul un paiement de CE compte (donc de ce tenant) peut être modifié.</li>
 * </ol>
 *
 * <p>Volontairement non {@code @Transactional} : l'audit d'un rejet (signature invalide) doit
 * être conservé même si une exception est levée juste après.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ProviderAccountService providerAccountService;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentStatusTransitionService statusTransitionService;
    private final PaymentLogService paymentLogService;
    private final PaymentNumberGenerator paymentNumberGenerator;
    private final CustomerService customerService;

    public void handle(PaymentProvider provider, String providerAccountId, String rawPayload, String signatureHeader) {
        ProviderAccount account = TenantContext.callAsSystem(() -> providerAccountRepository.findById(providerAccountId))
                .filter(a -> a.getProvider() == provider)
                .orElseThrow(() -> new ResourceNotFoundException("Compte prestataire", providerAccountId));
        TenantContext.runAs(account.getOrganizationId(), () -> process(provider, account, rawPayload, signatureHeader));
    }

    private void process(PaymentProvider provider, ProviderAccount account, String rawPayload, String signatureHeader) {
        long start = System.currentTimeMillis();
        PaymentProviderClient client = providerRegistry.resolve(provider);
        ProviderConnection connection = providerAccountService.toConnection(account);
        String correlationId = CorrelationContext.current();

        if (!client.verifyWebhookSignature(connection, rawPayload, signatureHeader)) {
            webhookEventRepository.save(baseEvent(provider, account, rawPayload, signatureHeader, correlationId)
                    .signatureValid(false)
                    .status(WebhookStatus.INVALID_SIGNATURE)
                    .build());
            log.warn("[WEBHOOK_INVALID_SIGNATURE] Signature invalide pour le compte {} ({})", account.getId(), provider);
            throw new InvalidWebhookSignatureException("Signature webhook invalide");
        }

        WebhookParseResult parsed = client.parseWebhookEvent(rawPayload);
        log.info("[WEBHOOK_PARSED] compte={} eventId={} txId={} status={}",
                account.getId(), parsed.providerEventId(), parsed.providerTransactionId(), parsed.status());

        if (parsed.providerEventId() != null) {
            Optional<WebhookEvent> previous = webhookEventRepository.findByProviderAndProviderAccountIdAndProviderEventId(
                    provider, account.getId(), parsed.providerEventId());
            if (previous.isPresent()) {
                log.info("[WEBHOOK_DUPLICATE_IGNORED] Événement {} déjà reçu pour le compte {}", parsed.providerEventId(), account.getId());
                return;
            }
        }

        WebhookEvent event = webhookEventRepository.save(baseEvent(provider, account, rawPayload, signatureHeader, correlationId)
                .providerEventId(parsed.providerEventId())
                .signatureValid(true)
                .status(WebhookStatus.RECEIVED)
                .build());
        applyToPayment(provider, account, client, connection, parsed, event, rawPayload);
        log.info("[WEBHOOK_HANDLE_COMPLETE] Webhook {} traité en {}ms", provider, System.currentTimeMillis() - start);
    }

    private void applyToPayment(PaymentProvider provider, ProviderAccount account, PaymentProviderClient client,
                                ProviderConnection connection, WebhookParseResult parsed, WebhookEvent event, String rawPayload) {
        if (parsed.providerTransactionId() == null || parsed.providerTransactionId().isBlank()) {
            markFailed(event, "Webhook sans providerTransactionId exploitable (ex : événement de test)");
            return;
        }

        // Recherche bornée au compte destinataire (et donc au tenant courant).
        Optional<Payment> found = paymentRepository.findByProviderAccountIdAndProviderTransactionId(
                account.getId(), parsed.providerTransactionId());
        Payment payment;
        if (found.isPresent()) {
            payment = found.get();
        } else if (parsed.unattributed() != null) {
            payment = createUnattributedPayment(provider, account, parsed);
        } else {
            markFailed(event, "Aucun paiement de ce compte pour providerTransactionId=" + parsed.providerTransactionId());
            log.error("[WEBHOOK_PAYMENT_NOT_FOUND] compte={} txId={}", account.getId(), parsed.providerTransactionId());
            return;
        }

        event.setPayment(payment);
        paymentLogService.info(payment, "WEBHOOK_RECEIVED", "Webhook reçu de " + provider, rawPayload);

        // Orange Money : le vrai transactionId n'arrive que dans le webhook (voir OrangeMoneyClient).
        if (!parsed.providerTransactionId().equals(payment.getProviderTransactionId())) {
            payment.setProviderTransactionId(parsed.providerTransactionId());
            payment = paymentRepository.save(payment);
        }

        payment = statusTransitionService.transitionStatus(payment, parsed.status(), "Webhook " + provider, TriggeredBy.WEBHOOK);
        if (parsed.status() == PaymentStatus.FAILED && parsed.failureReason() != null) {
            payment.setFailureReason(parsed.failureReason());
            payment = paymentRepository.save(payment);
        }
        if (provider == PaymentProvider.ORANGE_MONEY && parsed.status() == PaymentStatus.SUCCESS) {
            enrichActualPayerPhone(client, connection, payment);
        }

        event.setStatus(WebhookStatus.PROCESSED);
        event.setProcessedAt(LocalDateTime.now());
        webhookEventRepository.save(event);
    }

    /**
     * Le webhook Orange Money ne porte pas le numéro réellement débité ({@code subscriberMsisdn}),
     * disponible seulement via la recherche de transaction. Best effort.
     */
    private void enrichActualPayerPhone(PaymentProviderClient client, ProviderConnection connection, Payment payment) {
        try {
            String actual = client.checkStatus(connection, payment).actualPayerPhone();
            if (actual != null && !actual.isBlank() && !actual.equals(payment.getActualPayerPhone())) {
                payment.setActualPayerPhone(actual);
                paymentRepository.save(payment);
            }
        } catch (Exception e) {
            log.warn("[WEBHOOK_ENRICH_PHONE_FAILED] paiement {} : {}", payment.getId(), e.getMessage());
        }
    }

    /**
     * Paiement reçu directement sur le compte marchand, sans initiation Paylance (ex : lien de
     * paiement Wave partagé à la main) : créé dans l'organisation du compte, sans application.
     */
    private Payment createUnattributedPayment(PaymentProvider provider, ProviderAccount account, WebhookParseResult parsed) {
        WebhookParseResult.UnattributedPayment unattributed = parsed.unattributed();
        String payerPhone = unattributed.payerPhone() != null && !unattributed.payerPhone().isBlank()
                ? unattributed.payerPhone() : "inconnu";
        String customerId = unattributed.payerPhone() != null && !unattributed.payerPhone().isBlank()
                ? customerService.upsert(unattributed.payerPhone(), null, null, null) : null;
        Payment payment = paymentRepository.save(Payment.builder()
                .paymentNumber(paymentNumberGenerator.generate())
                .idempotencyKey(provider + "-UNATTRIBUTED-" + parsed.providerTransactionId())
                .correlationId(CorrelationContext.current())
                .providerAccountId(account.getId())
                .environment(account.getEnvironment())
                .customerId(customerId)
                .provider(provider)
                .providerTransactionId(parsed.providerTransactionId())
                .amount(unattributed.amount())
                .currency(unattributed.currency() != null ? unattributed.currency() : "XOF")
                .payerPhone(payerPhone)
                .status(PaymentStatus.PENDING)
                .build());
        paymentLogService.info(payment, "PAYMENT_RECEIVED_UNATTRIBUTED",
                "Paiement reçu directement sur le compte " + provider + ", hors initiation Paylance");
        return payment;
    }

    private void markFailed(WebhookEvent event, String error) {
        event.setStatus(WebhookStatus.FAILED);
        event.setProcessingError(error);
        webhookEventRepository.save(event);
    }

    private static WebhookEvent.WebhookEventBuilder baseEvent(PaymentProvider provider, ProviderAccount account,
                                                              String rawPayload, String signatureHeader, String correlationId) {
        return WebhookEvent.builder()
                .provider(provider)
                .providerAccountId(account.getId())
                .signature(signatureHeader != null && signatureHeader.length() > 500 ? signatureHeader.substring(0, 500) : signatureHeader)
                .rawPayload(rawPayload)
                .correlationId(correlationId);
    }
}
