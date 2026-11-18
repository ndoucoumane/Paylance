package com.paylance.web.controller;

import com.paylance.service.OrganizationService;
import com.paylance.web.dto.onboarding.OrganizationCreateRequest;
import com.paylance.web.dto.onboarding.OrganizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Organisations de l'utilisateur connecté. L'identité vient exclusivement du jeton validé
 * ({@code sub}) ; aucun identifiant d'utilisateur n'est lu dans la requête. Accès réservé aux
 * comptes à l'e-mail vérifié (SecurityConfig).
 */
@Tag(name = "Organisations", description = "Création et liste des organisations de l'utilisateur")
@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @Operation(summary = "Organisations dont l'utilisateur est membre, avec son rôle")
    @GetMapping
    public List<OrganizationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return organizationService.listForUser(jwt.getSubject()).stream()
                .map(m -> OrganizationResponse.of(m.organization(), m.role()))
                .toList();
    }

    @Operation(summary = "Créer une organisation ; l'utilisateur en devient OWNER")
    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody OrganizationCreateRequest request) {
        OrganizationService.Membership membership = organizationService.create(request.name(), request.slug(),
                request.country(), request.defaultCurrency(), jwt.getSubject(), jwt.getClaimAsString("email"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OrganizationResponse.of(membership.organization(), membership.role()));
    }
}
