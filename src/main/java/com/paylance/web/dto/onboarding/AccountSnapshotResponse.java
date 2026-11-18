package com.paylance.web.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * État de session du front ({@code GET /api/v1/me}) : il décide seul de la page à afficher à
 * partir de cette réponse. {@code organization} est sérialisé même à {@code null} (le front
 * teste {@code organization === null}).
 */
public record AccountSnapshotResponse(
        User user,
        @JsonInclude(JsonInclude.Include.ALWAYS) OrganizationResponse organization,
        Progress progress) {

    public record User(String id, String firstName, String lastName, String email, boolean emailVerified) {
    }

    public record Progress(OnboardingStep currentStep, List<OnboardingStep> completedSteps, boolean providersSkipped) {
    }

    public enum OnboardingStep {
        COMPANY, PREFERENCES, PLAN, PROVIDERS, DONE
    }
}
