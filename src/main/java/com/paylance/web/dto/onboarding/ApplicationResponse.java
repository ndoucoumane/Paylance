package com.paylance.web.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paylance.domain.entity.Application;
import com.paylance.domain.enums.EnvironmentType;

import java.time.LocalDateTime;

/** {@code signingSecret} n'est renseigné qu'à la création et à la rotation (affiché une seule fois). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationResponse(String id, String name, String slug, EnvironmentType environment, String callbackUrl,
                                  String successUrl, String errorUrl, Boolean active, String signingSecret,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static ApplicationResponse of(Application a, String signingSecret) {
        return new ApplicationResponse(a.getId(), a.getName(), a.getSlug(), a.getEnvironment(), a.getCallbackUrl(),
                a.getSuccessUrl(), a.getErrorUrl(), a.getActive(), signingSecret, a.getCreatedAt(), a.getUpdatedAt());
    }
}
