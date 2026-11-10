package com.paylance.domain.entity;

import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Versement (payout) programmé depuis le dashboard : enregistré au statut {@code SCHEDULED},
 * puis exécuté (véritable appel provider via {@code PaymentService#initiate}) par
 * {@code ScheduledPaymentService} dès que {@link #scheduledAt} est atteint.
 */
@Entity
@EntityListeners(TenantGuardListener.class)
@Table(name = "scheduled_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledPayment implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    @Column(name = "provider_account_id", nullable = false, length = 36)
    private String providerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    /** Montant en centimes (cohérent avec {@link Payment#getAmount()}). */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "payer_phone", nullable = false, length = 20)
    private String payerPhone;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "note", length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ScheduledPaymentStatus status = ScheduledPaymentStatus.SCHEDULED;

    /** Renseigné une fois exécuté : identifiant du {@link Payment} réellement créé chez le provider. */
    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
