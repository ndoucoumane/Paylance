package com.kebpay.domain.entity;

import com.kebpay.domain.enums.PaymentStatus;
import com.kebpay.domain.enums.TriggeredBy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Trace immuable de chaque transition de statut d'un paiement (audit + reconstruction du parcours).
 */
@Entity
@Table(name = "payment_status_history", indexes = {
        @Index(name = "idx_status_history_payment", columnList = "payment_id"),
        @Index(name = "idx_status_history_date",    columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false)
    private TriggeredBy triggeredBy;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata; // JSON optionnel

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
