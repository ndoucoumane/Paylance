package com.paylance.domain.entity;

import com.paylance.domain.enums.EnvironmentType;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Application déclarée par un client (son site, son app mobile, son ERP...). Son nom est libre :
 * aucune application n'est connue de Paylance à l'avance.
 */
@Entity
@Table(name = "applications")
@EntityListeners(TenantGuardListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 10)
    private EnvironmentType environment;

    /** URL notifiée (callback signé) à chaque changement de statut final d'un paiement. */
    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    @Column(name = "success_url", length = 500)
    private String successUrl;

    @Column(name = "error_url", length = 500)
    private String errorUrl;

    /** Secret HMAC des callbacks sortants, chiffré (AES-GCM) — voir EncryptionService. */
    @Column(name = "signing_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String signingSecretEncrypted;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
