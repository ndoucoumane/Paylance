package com.paylance.repository;

import com.paylance.domain.entity.OrganizationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, String> {

    /** Tenant courant : le membre correspondant à cet utilisateur, s'il existe. */
    Optional<OrganizationMember> findByKeycloakUserId(String keycloakUserId);

    /** À appeler en mode système : toutes les appartenances d'un utilisateur. */
    List<OrganizationMember> findAllByKeycloakUserIdOrderByCreatedAtAscIdAsc(String keycloakUserId);

    List<OrganizationMember> findAllByOrderByCreatedAtAsc();

    long countByRole(com.paylance.domain.enums.MemberRole role);
}
