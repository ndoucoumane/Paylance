package com.kebpay.domain.entity;

import com.kebpay.domain.enums.LogLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Journal détaillé de chaque événement technique d'une transaction
 * (requête d'initiation, appel provider, webhook, callback, reconciliation...).
 * Sert de trace complète pour le debug et l'audit — exigence centrale de KebPay.
 */
@Entity
@Table(name = "payment_logs", indexes = {
        @Index(name = "idx_payment_logs_payment",  columnList = "payment_id"),
        @Index(name = "idx_payment_logs_date",     columnList = "created_at"),
        @Index(name = "idx_payment_logs_correlation", columnList = "correlation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

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
