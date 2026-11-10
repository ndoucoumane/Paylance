package com.kebpay.domain.entity;

import com.kebpay.domain.enums.Platform;
import com.kebpay.domain.enums.PaymentProvider;
import com.kebpay.domain.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Transaction de paiement orchestrée par KebPay entre une plateforme interne
 * (Afrisurgelée, Kssid, Fleet360, ...) et un provider mobile money (Wave, Orange Money).
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_idempotency",     columnList = "platform, idempotency_key", unique = true),
        @Index(name = "idx_payments_platform",        columnList = "platform"),
        @Index(name = "idx_payments_status",          columnList = "status"),
        @Index(name = "idx_payments_external_ref",    columnList = "external_reference"),
        @Index(name = "idx_payments_provider_txid",   columnList = "provider_transaction_id"),
        @Index(name = "idx_payments_correlation_id",  columnList = "correlation_id"),
        @Index(name = "idx_payments_created_at",      columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Numéro lisible affiché côté dashboard/front (ex: {@code PAY-AFR-0001}) — {@link #id}
     * reste la vraie clé technique et la référence de corrélation provider. */
    @Column(name = "payment_number", length = 20, unique = true)
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

    // ── Contexte plateforme ──
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private Platform platform;

    @Column(name = "external_reference", length = 100)
    private String externalReference; // orderId, bookingId...

    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata; // JSON libre transmis par la plateforme (items, userId...)

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
    private Long amount; // en centimes XOF — jamais de Double

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
     * par la plateforme à la création de la commande. Toujours {@code null} pour Wave. */
    @Column(name = "actual_payer_phone", length = 20)
    private String actualPayerPhone;

    // ── Statut ──
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // ── Callback vers plateforme ──
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
