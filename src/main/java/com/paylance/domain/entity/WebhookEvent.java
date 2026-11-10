package com.paylance.domain.entity;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.WebhookStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit brut de chaque webhook reçu d'un provider, avant tout traitement métier.
 * Sert à rendre le traitement idempotent (les providers peuvent renvoyer plusieurs
 * fois le même événement) et à conserver une preuve du payload + de la signature reçus.
 */
@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_webhook_events_provider_event", columnList = "provider, provider_event_id"),
        @Index(name = "idx_webhook_events_payment",         columnList = "payment_id"),
        @Index(name = "idx_webhook_events_status",          columnList = "status"),
        @Index(name = "idx_webhook_events_created_at",      columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    /**
     * Identifiant d'événement fourni par le provider (si disponible), utilisé
     * pour la déduplication en plus du hash de signature.
     */
    @Column(name = "provider_event_id", length = 200)
    private String providerEventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "signature", length = 500)
    private String signature;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Column(name = "raw_payload", columnDefinition = "TEXT", nullable = false)
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WebhookStatus status = WebhookStatus.RECEIVED;

    @Column(name = "processing_error", length = 1000)
    private String processingError;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
