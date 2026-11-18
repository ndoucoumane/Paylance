package com.paylance.domain.entity;

import com.paylance.domain.enums.LogLevel;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Journal détaillé de chaque événement technique d'une transaction
 * (requête d'initiation, appel provider, webhook, callback, reconciliation...).
 * Sert de trace complète pour le debug et l'audit — exigence centrale de Paylance.
 */
@Entity
@EntityListeners(TenantGuardListener.class)
@Table(name = "payment_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLog implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    private LogLevel level;

    @Column(name = "event", nullable = false, length = 100)
    private String event; // INITIATE_REQUEST, PROVIDER_CALL, WEBHOOK_RECEIVED, CALLBACK_SENT...

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload; // JSON brut envoyé/reçu

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
