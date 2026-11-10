package com.paylance.domain.entity;

import com.paylance.domain.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Remboursement (total ou partiel) d'un paiement, initié via le provider d'origine.
 */
@Entity
@Table(name = "refunds", indexes = {
        @Index(name = "idx_refunds_payment", columnList = "payment_id"),
        @Index(name = "idx_refunds_status",  columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "amount", nullable = false)
    private Long amount; // en centimes, <= montant restant remboursable du paiement

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "provider_refund_id", length = 200)
    private String providerRefundId;

    @Column(name = "provider_raw_response", columnDefinition = "TEXT")
    private String providerRawResponse;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "requested_by", length = 150)
    private String requestedBy; // identifiant/email de l'opérateur ou "API"

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
