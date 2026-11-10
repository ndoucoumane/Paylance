package com.paylance.domain.entity;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.WebhookStatus;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import org.hibernate.annotations.TenantId;
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
@EntityListeners(TenantGuardListener.class)
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    /** Compte prestataire destinataire du webhook (porté par l'URL appelée). */
    @Column(name = "provider_account_id", nullable = false, length = 36)
    private String providerAccountId;

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
