package com.paylance.domain.entity;

import com.paylance.domain.enums.MemberRole;
import com.paylance.tenant.TenantGuardListener;
import com.paylance.tenant.TenantOwned;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Utilisateur Keycloak membre d'une organisation, avec son rôle. */
@Entity
@Table(name = "organization_members")
@EntityListeners(TenantGuardListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationMember implements TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false, length = 36)
    private String organizationId;

    /** Claim {@code sub} du JWT Keycloak. */
    @Column(name = "keycloak_user_id", nullable = false, length = 100)
    private String keycloakUserId;

    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
