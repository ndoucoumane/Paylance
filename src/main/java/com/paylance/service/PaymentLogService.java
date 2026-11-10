package com.paylance.service;

import com.paylance.common.CorrelationContext;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.PaymentLog;
import com.paylance.domain.enums.LogLevel;
import com.paylance.repository.PaymentLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point d'écriture unique de la trace applicative d'une transaction : persiste un
 * {@link PaymentLog} en base ET émet un log structuré (JSON) avec le correlationId,
 * pour que toute l'histoire d'un paiement soit reconstructible à la fois en base et
 * dans l'outil de log agrégé (ELK/Loki/...).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLogService {

    private final PaymentLogRepository paymentLogRepository;

    // Chaque méthode publique porte directement @Transactional(REQUIRES_NEW) — plutôt que de
    // déléguer à une méthode "log()" interne commune — pour éviter le piège classique de l'auto-
    // invocation Spring AOP : un appel `this.log(...)` depuis l'intérieur de la même classe
    // contourne le proxy et désactive silencieusement REQUIRES_NEW, faisant retomber l'écriture
    // du log dans la transaction appelante (donc annulée si celle-ci échoue — inacceptable ici,
    // le but même de ce service est de tracer la transaction y compris en cas d'échec).

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(Payment payment, String event, String message) {
        persistAndEmit(payment, LogLevel.INFO, event, message, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(Payment payment, String event, String message, String payload) {
        persistAndEmit(payment, LogLevel.INFO, event, message, payload, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(Payment payment, String event, String message, String payload, Long durationMs) {
        persistAndEmit(payment, LogLevel.INFO, event, message, payload, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void warn(Payment payment, String event, String message) {
        persistAndEmit(payment, LogLevel.WARN, event, message, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void warn(Payment payment, String event, String message, Long durationMs) {
        persistAndEmit(payment, LogLevel.WARN, event, message, null, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(Payment payment, String event, String message, String payload) {
        persistAndEmit(payment, LogLevel.ERROR, event, message, payload, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(Payment payment, String event, String message, String payload, Long durationMs) {
        persistAndEmit(payment, LogLevel.ERROR, event, message, payload, durationMs);
    }

    private void persistAndEmit(Payment payment, LogLevel level, String event, String message, String payload, Long durationMs) {
        String correlationId = payment != null ? payment.getCorrelationId() : CorrelationContext.current();
        String formattedMessage = (durationMs != null) ? message + " [durée: " + durationMs + "ms]" : message;

        PaymentLog entry = PaymentLog.builder()
                .payment(payment)
                .correlationId(correlationId)
                .level(level)
                .event(event)
                .message(formattedMessage)
                .payload(payload)
                .build();
        paymentLogRepository.save(entry);

        emitStructuredLog(payment, level, event, formattedMessage, correlationId, durationMs);
    }

    private void emitStructuredLog(Payment payment, LogLevel level, String event, String message, String correlationId, Long durationMs) {
        String paymentId = payment != null ? payment.getId() : null;
        if (durationMs != null) {
            switch (level) {
                case ERROR -> log.error(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId),
                        StructuredArguments.kv("durationMs", durationMs));
                case WARN -> log.warn(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId),
                        StructuredArguments.kv("durationMs", durationMs));
                default -> log.info(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId),
                        StructuredArguments.kv("durationMs", durationMs));
            }
        } else {
            switch (level) {
                case ERROR -> log.error(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId));
                case WARN -> log.warn(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId));
                default -> log.info(message,
                        StructuredArguments.kv("event", event),
                        StructuredArguments.kv("paymentId", paymentId),
                        StructuredArguments.kv("correlationId", correlationId));
            }
        }
    }
}
