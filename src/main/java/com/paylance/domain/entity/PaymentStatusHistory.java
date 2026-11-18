package com.paylance.domain.entity;

import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.TriggeredBy;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Trace immuable de chaque transition de statut d'un paiement (audit + reconstruction du parcours).
 */
@Entity
@EntityListeners(TenantGuardListener.class)
@Table(name = "payment_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentStatusHistory implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

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
