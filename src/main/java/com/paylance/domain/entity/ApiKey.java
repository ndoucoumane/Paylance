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
 * Clé API d'une application. Format de la clé en clair :
 * {@code pl_live_<identifiant>_<secret>} (ou {@code pl_test_}). Seuls le préfixe
 * ({@code pl_live_<identifiant>}, sert à retrouver la ligne), les 4 derniers caractères et le
 * hash BCrypt de la clé complète sont stockés : la clé en clair n'est affichée qu'une fois.
 */
@Entity
@Table(name = "api_keys")
@EntityListeners(TenantGuardListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    @Column(name = "application_id", nullable = false, length = 36)
    private String applicationId;

    @Column(name = "label", length = 150)
    private String label;

    @Column(name = "prefix", nullable = false, unique = true, length = 40)
    private String prefix;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(name = "key_hash", nullable = false, length = 100)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 10)
    private EnvironmentType environment;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isUsable(LocalDateTime now) {
        return Boolean.TRUE.equals(active) && revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
