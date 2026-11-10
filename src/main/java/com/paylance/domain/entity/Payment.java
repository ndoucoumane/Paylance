package com.paylance.domain.entity;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Paiement orchestré par Paylance pour le compte d'une organisation cliente, encaissé sur le
 * compte marchand de ce client chez le prestataire (Wave, Orange Money).
 */
@Entity
@EntityListeners(TenantGuardListener.class)
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    /** Application émettrice ; {@code null} pour un paiement créé depuis le dashboard ou reçu
     * directement sur le compte marchand (lien de paiement partagé à la main). */
    @Column(name = "application_id", length = 36)
    private String applicationId;

    /** Compte marchand du client qui encaisse : les fonds ne transitent jamais par Paylance. */
    @Column(name = "provider_account_id", nullable = false, length = 36)
    private String providerAccountId;

    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 10)
    private EnvironmentType environment;

    /** Numéro lisible affiché côté dashboard/front (ex: {@code PL-261110-000001-7}) — {@link #id}
     * reste la vraie clé technique et la référence de corrélation provider. */
    @Column(name = "payment_number", length = 30, unique = true)
    private String paymentNumber;

    /**
     * Verrou optimiste : un webhook et le job de reconciliation peuvent tenter
     * de transitionner le même paiement en concurrence.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * Identifiant de traçage propagé de bout en bout (MDC) : requête d'initiation,
     * appel provider, webhook, callback, reconciliation. Permet de retrouver
     * toute l'histoire d'une transaction dans les logs structurés.
     */
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    // ── Contexte client ──
    @Column(name = "external_reference", length = 100)
    private String externalReference; // orderId, bookingId...

    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    /** URL de redirection du payeur après succès, transmise au prestataire à l'initiation. */
    @Column(name = "success_url", length = 500)
    private String successUrl;

    /** URL de redirection du payeur après échec ou annulation. */
    @Column(name = "error_url", length = 500)
    private String errorUrl;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata; // JSON libre transmis par le client (items, userId...)

    // ── Provider ──
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    @Column(name = "provider_transaction_id", length = 200)
    private String providerTransactionId;

    @Column(name = "provider_raw_response", columnDefinition = "TEXT")
    private String providerRawResponse;

    // ── Montant ──
    @Column(name = "amount", nullable = false)
    private Long amount; // en centimes — jamais de Double

    @Column(name = "currency", nullable = false, length = 10)
    @Builder.Default
    private String currency = "XOF";

    // ── Payeur ──
    @Column(name = "payer_phone", nullable = false, length = 20)
    private String payerPhone;

    @Column(name = "payer_name", length = 100)
    private String payerName;

    /** Numéro ayant effectivement réglé le paiement côté provider, quand celui-ci le communique
     * (Orange Money via {@code subscriberMsisdn}) — peut différer de {@link #payerPhone}, déclaré
     * par le client à la création de la commande. Toujours {@code null} pour Wave. */
    @Column(name = "actual_payer_phone", length = 20)
    private String actualPayerPhone;

    // ── Statut ──
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // ── Callback vers l'application ──
    @Column(name = "callback_attempts")
    @Builder.Default
    private Integer callbackAttempts = 0;

    @Column(name = "last_callback_at")
    private LocalDateTime lastCallbackAt;

    @Column(name = "callback_success")
    @Builder.Default
    private Boolean callbackSuccess = false;

    // ── Dates ──
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Relations ──
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentStatusHistory> statusHistory = new ArrayList<>();

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentLog> logs = new ArrayList<>();

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Refund> refunds = new ArrayList<>();
}
