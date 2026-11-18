package com.paylance.service;

import com.paylance.common.Slugs;
import com.paylance.domain.entity.Application;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.repository.ApplicationRepository;
import com.paylance.security.crypto.EncryptionService;
import com.paylance.tenant.TenantContext;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Applications déclarées par les clients. Chaque application a son secret de signature des
 * callbacks sortants, chiffré en base et affiché en clair une seule fois (création / rotation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    public record ApplicationWithSecret(Application application, String signingSecret) {
    }

    private final ApplicationRepository repository;
    private final EncryptionService encryptionService;
    private final CallbackUrlValidator callbackUrlValidator;
    private final SecureRandom random = new SecureRandom();

    public ApplicationWithSecret create(String name, String requestedSlug, EnvironmentType environment,
                                       String callbackUrl, String successUrl, String errorUrl) {
        String organizationId = TenantContext.requireOrganizationId();
        validateUrls(environment, callbackUrl, successUrl, errorUrl);
        String secret = newSigningSecret();
        Application application = repository.save(Application.builder()
                .name(name.trim())
                .slug(uniqueSlug(requestedSlug != null && !requestedSlug.isBlank() ? requestedSlug : name))
                .environment(environment)
                .callbackUrl(blankToNull(callbackUrl))
                .successUrl(blankToNull(successUrl))
                .errorUrl(blankToNull(errorUrl))
                .signingSecretEncrypted(encryptionService.encrypt(secret, signingSecretAad(organizationId)))
                .build());
        log.info("[APPLICATION_CREATED] {} ({}, {})", application.getId(), application.getSlug(), environment);
        return new ApplicationWithSecret(application, secret);
    }

    /** Champs {@code null} = inchangés ; une chaîne vide efface une URL. */
    public Application update(String id, String name, String callbackUrl, String successUrl, String errorUrl, Boolean active) {
        Application application = get(id);
        validateUrls(application.getEnvironment(), callbackUrl, successUrl, errorUrl);
        if (name != null && !name.isBlank()) application.setName(name.trim());
        if (callbackUrl != null) application.setCallbackUrl(blankToNull(callbackUrl));
        if (successUrl != null) application.setSuccessUrl(blankToNull(successUrl));
        if (errorUrl != null) application.setErrorUrl(blankToNull(errorUrl));
        if (active != null) application.setActive(active);
        return repository.save(application);
    }

    public ApplicationWithSecret rotateSigningSecret(String id) {
        Application application = get(id);
        String secret = newSigningSecret();
        application.setSigningSecretEncrypted(encryptionService.encrypt(secret, signingSecretAad(application.getOrganizationId())));
        log.info("[APPLICATION_SECRET_ROTATED] {}", id);
        return new ApplicationWithSecret(repository.save(application), secret);
    }

    public String signingSecret(Application application) {
        return encryptionService.decrypt(application.getSigningSecretEncrypted(), signingSecretAad(application.getOrganizationId()));
    }

    public Application get(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application", id));
    }

    public List<Application> list() {
        return repository.findAllByOrderByCreatedAtAsc();
    }

    private void validateUrls(EnvironmentType environment, String callbackUrl, String successUrl, String errorUrl) {
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            callbackUrlValidator.validate(callbackUrl, environment);
        }
        CallbackUrlValidator.requireHttpUrl(successUrl, "successUrl");
        CallbackUrlValidator.requireHttpUrl(errorUrl, "errorUrl");
    }

    private String newSigningSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String uniqueSlug(String source) {
        String base = Slugs.slugify(source);
        if (base.isBlank()) {
            base = "app";
        }
        String candidate = base;
        while (repository.existsBySlug(candidate)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return candidate;
    }

    static String signingSecretAad(String organizationId) {
        return organizationId + ":application.signing_secret";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
