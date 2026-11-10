package com.kebpay.domain.entity;

import com.kebpay.domain.enums.Platform;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Clé API émise pour une plateforme cliente (Afrisurgelée, Kssid, Fleet360...).
 * La clé n'est jamais stockée en clair : seul son hash BCrypt est persisté.
 */
@Entity
@Table(name = "platform_api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, unique = true)
    private Platform platform;

    @Column(name = "label", length = 150)
    private String label; // ex: "Afrisurgelée - prod", utile pour l'audit humain

    @Column(name = "api_key_hash", nullable = false, unique = true, length = 200)
    private String apiKeyHash; // hash BCrypt de la clé — jamais la clé en clair

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
