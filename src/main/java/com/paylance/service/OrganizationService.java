package com.paylance.service;

import com.paylance.common.ReferenceData;
import com.paylance.common.Slugs;
import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.OrganizationMember;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.OrganizationStatus;
import com.paylance.repository.OrganizationMemberRepository;
import com.paylance.repository.OrganizationRepository;
import com.paylance.tenant.TenantContext;
import com.paylance.web.exception.BusinessRuleException;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Organisations (tenants) et leurs membres. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    public record Membership(Organization organization, MemberRole role) {
    }

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    /** Crée une organisation ; l'utilisateur qui la crée en devient OWNER. */
    public Membership create(String name, String requestedSlug, String country, String defaultCurrency,
                             String keycloakUserId, String email) {
        String normalizedCountry = country == null ? null : country.trim().toUpperCase();
        if (!ReferenceData.isSupportedCountry(normalizedCountry)) {
            throw new BusinessRuleException("Ce pays n'est pas encore pris en charge.");
        }
        String currency = defaultCurrency != null && !defaultCurrency.isBlank()
                ? defaultCurrency.trim().toUpperCase()
                : ReferenceData.defaultCurrencyFor(normalizedCountry);
        if (!ReferenceData.isSupportedCurrency(currency)) {
            throw new BusinessRuleException("Cette devise n'est pas prise en charge.");
        }
        String slug = uniqueSlug(requestedSlug != null && !requestedSlug.isBlank() ? requestedSlug : name);
        Organization organization = organizationRepository.save(Organization.builder()
                .name(name.trim())
                .slug(slug)
                .country(normalizedCountry)
                .defaultCurrency(currency)
                .status(OrganizationStatus.ACTIVE)
                .build());
        TenantContext.runAs(organization.getId(), () -> memberRepository.save(OrganizationMember.builder()
                .keycloakUserId(keycloakUserId)
                .email(email)
                .role(MemberRole.OWNER)
                .build()));
        log.info("[ORGANIZATION_CREATED] {} ({}) créée par {}", organization.getId(), slug, keycloakUserId);
        return new Membership(organization, MemberRole.OWNER);
    }

    /**
     * Organisations dont l'utilisateur est membre (lecture transverse, mode système), de la plus
     * ancienne adhésion à la plus récente. Ordre stable : le front prend la première, aussi bien
     * dans {@code GET /organizations} que dans {@code GET /me}.
     */
    public List<Membership> listForUser(String keycloakUserId) {
        List<OrganizationMember> memberships = TenantContext.callAsSystem(
                () -> memberRepository.findAllByKeycloakUserIdOrderByCreatedAtAscIdAsc(keycloakUserId));
        Map<String, Organization> organizations = organizationRepository
                .findAllById(memberships.stream().map(OrganizationMember::getOrganizationId).toList()).stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        return memberships.stream()
                .filter(m -> organizations.containsKey(m.getOrganizationId()))
                .map(m -> new Membership(organizations.get(m.getOrganizationId()), m.getRole()))
                .toList();
    }

    /** Rôle de l'utilisateur dans l'organisation (vide s'il n'en est pas membre). */
    public Optional<MemberRole> roleOf(String organizationId, String keycloakUserId) {
        return TenantContext.callAs(organizationId, () -> memberRepository.findByKeycloakUserId(keycloakUserId))
                .map(OrganizationMember::getRole);
    }

    public Organization current() {
        String id = TenantContext.requireOrganizationId();
        return organizationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organisation", id));
    }

    public Organization update(String name, String defaultCurrency) {
        Organization organization = current();
        if (name != null && !name.isBlank()) {
            organization.setName(name.trim());
        }
        if (defaultCurrency != null && !defaultCurrency.isBlank()) {
            organization.setDefaultCurrency(defaultCurrency.toUpperCase());
        }
        return organizationRepository.save(organization);
    }

    public List<OrganizationMember> members() {
        return memberRepository.findAllByOrderByCreatedAtAsc();
    }

    public OrganizationMember addMember(String keycloakUserId, String email, MemberRole role, MemberRole actorRole) {
        requireCanGrant(actorRole, role);
        if (memberRepository.findByKeycloakUserId(keycloakUserId).isPresent()) {
            throw new IllegalStateException("Cet utilisateur est déjà membre de l'organisation");
        }
        return memberRepository.save(OrganizationMember.builder().keycloakUserId(keycloakUserId).email(email).role(role).build());
    }

    public OrganizationMember changeRole(String memberId, MemberRole newRole, MemberRole actorRole) {
        OrganizationMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Membre", memberId));
        requireCanGrant(actorRole, newRole);
        requireCanGrant(actorRole, member.getRole());
        if (member.getRole() == MemberRole.OWNER && newRole != MemberRole.OWNER) {
            requireAnotherOwner();
        }
        member.setRole(newRole);
        return memberRepository.save(member);
    }

    public void removeMember(String memberId, MemberRole actorRole) {
        OrganizationMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Membre", memberId));
        requireCanGrant(actorRole, member.getRole());
        if (member.getRole() == MemberRole.OWNER) {
            requireAnotherOwner();
        }
        memberRepository.delete(member);
    }

    /** Seul un OWNER peut attribuer ou retirer le rôle OWNER. */
    private static void requireCanGrant(MemberRole actorRole, MemberRole targetRole) {
        if (targetRole == MemberRole.OWNER && actorRole != MemberRole.OWNER) {
            throw new org.springframework.security.access.AccessDeniedException("Seul un OWNER peut gérer le rôle OWNER");
        }
    }

    private void requireAnotherOwner() {
        if (memberRepository.countByRole(MemberRole.OWNER) <= 1) {
            throw new IllegalStateException("L'organisation doit garder au moins un OWNER");
        }
    }

    private String uniqueSlug(String source) {
        String base = Slugs.slugify(source);
        if (base.isBlank()) {
            base = "org";
        }
        String candidate = base;
        while (organizationRepository.existsBySlug(candidate)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return candidate;
    }
}
