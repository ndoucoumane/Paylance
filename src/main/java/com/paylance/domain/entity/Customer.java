package com.paylance.domain.entity;

import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Payeur d'un client, identifié par son téléphone au sein de l'organisation (upsert à chaque paiement). */
@Entity
@Table(name = "customers")
@EntityListeners(TenantGuardListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    /** Identifiant du payeur dans le système du client. */
    @Column(name = "external_id", length = 100)
    private String externalId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
