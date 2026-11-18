package com.paylance.domain.entity;

import com.paylance.domain.enums.OrganizationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Client de Paylance (le tenant). Seule entité métier non filtrée par {@code @TenantId}. */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 80)
    private String slug;

    /** Code pays ISO 3166-1 alpha-2 (ex : SN). */
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "default_currency", nullable = false, length = 3)
    @Builder.Default
    private String defaultCurrency = "XOF";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
