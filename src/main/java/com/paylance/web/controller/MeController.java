package com.paylance.web.controller;

import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.repository.ProviderAccountRepository;
import com.paylance.security.OrganizationAccess;
import com.paylance.service.OrganizationService;
import com.paylance.tenant.TenantContext;
import com.paylance.web.dto.onboarding.AccountSnapshotResponse;
import com.paylance.web.dto.onboarding.AccountSnapshotResponse.OnboardingStep;
import com.paylance.web.dto.onboarding.AccountSnapshotResponse.Progress;
import com.paylance.web.dto.onboarding.OrganizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Version minimale de {@code GET /api/v1/me} (contrat front §2.2), compatible avec la connexion
 * actuelle par la page Keycloak.
 *
 * <ul>
 *   <li>{@code user} : lu dans le jeton validé. {@code emailVerified} vient de la même source que
 *   le contrôle d'accès (autorité {@link OrganizationAccess#EMAIL_VERIFIED}), pour que le front
 *   et le backend ne divergent jamais. Passera sur la table {@code users} quand elle existera.</li>
 *   <li>{@code organization} : la première organisation de l'utilisateur, lue en base, dans le
 *   même ordre que {@code GET /api/v1/organizations}.</li>
 *   <li>{@code progress} : simplifié tant que les étapes préférences / formule / prestataires
 *   n'ont pas de route : {@code COMPANY} sans organisation, {@code DONE} sinon.</li>
 * </ul>
 *
 * Accessible sans e-mail vérifié : c'est cette réponse qui envoie le front vers la vérification.
 */
@Tag(name = "Session", description = "État de la session pour le front")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private static final List<OnboardingStep> ALL_STEPS = List.of(OnboardingStep.values());

    private final OrganizationService organizationService;
    private final ProviderAccountRepository providerAccountRepository;

    @Operation(summary = "Utilisateur, organisation courante et progression de l'onboarding")
    @GetMapping
    public ResponseEntity<AccountSnapshotResponse> me(JwtAuthenticationToken authentication) {
        var jwt = authentication.getToken();
        boolean emailVerified = authentication.getAuthorities().stream()
                .anyMatch(a -> OrganizationAccess.EMAIL_VERIFIED.equals(a.getAuthority()));
        var user = new AccountSnapshotResponse.User(jwt.getSubject(), text(jwt.getClaimAsString("given_name")),
                text(jwt.getClaimAsString("family_name")), text(jwt.getClaimAsString("email")), emailVerified);

        // Pas d'organisation exposée tant que l'e-mail n'est pas vérifié : cohérent avec le refus
        // d'accès aux routes /organizations et /dashboard dans ce cas.
        OrganizationResponse organization = emailVerified
                ? organizationService.listForUser(jwt.getSubject()).stream()
                        .findFirst()
                        .map(m -> OrganizationResponse.of(m.organization(), m.role()))
                        .orElse(null)
                : null;

        Progress progress = organization == null
                ? new Progress(OnboardingStep.COMPANY, List.of(), false)
                : new Progress(OnboardingStep.DONE, ALL_STEPS, !hasActiveProviderAccount(organization.id()));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AccountSnapshotResponse(user, organization, progress));
    }

    private boolean hasActiveProviderAccount(String organizationId) {
        return TenantContext.callAs(organizationId,
                () -> providerAccountRepository.existsByStatus(ProviderAccountStatus.ACTIVE));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
