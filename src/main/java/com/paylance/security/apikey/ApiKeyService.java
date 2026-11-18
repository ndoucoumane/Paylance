package com.paylance.security.apikey;

import com.paylance.domain.entity.ApiKey;
import com.paylance.domain.entity.Application;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.OrganizationStatus;
import com.paylance.repository.ApiKeyRepository;
import com.paylance.repository.ApplicationRepository;
import com.paylance.repository.OrganizationRepository;
import com.paylance.tenant.TenantContext;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Émission, vérification et révocation des clés API d'application.
 *
 * <p>Format : {@code pl_live_<identifiant 12 car.>_<secret base64url 43 car.>}. L'identifiant
 * (dans le préfixe stocké en clair) permet de retrouver la ligne ; la clé complète est vérifiée
 * contre son hash BCrypt.
 *
 * <p>BCrypt coûte plusieurs dizaines de ms par vérification : une vérification réussie est mise
 * en cache 60 s en mémoire, indexée par l'empreinte SHA-256 de la clé (jamais la clé elle-même).
 * Une révocation vide immédiatement le cache de l'instance qui la traite ; sur les autres
 * instances, la clé reste acceptée au plus 60 s.
 */
@Slf4j
@Service
public class ApiKeyService {

    private static final int ID_LENGTH = 12;
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ApiKeyRepository apiKeyRepository;
    private final ApplicationRepository applicationRepository;
    private final OrganizationRepository organizationRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CachedAuthentication> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ApplicationRepository applicationRepository,
                         OrganizationRepository organizationRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.applicationRepository = applicationRepository;
        this.organizationRepository = organizationRepository;
    }

    public GeneratedApiKey create(String applicationId, String label, LocalDateTime expiresAt) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application", applicationId));
        EnvironmentType environment = application.getEnvironment();

        String identifier = randomIdentifier();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String prefix = environment.apiKeyPrefix() + identifier;
        String plaintext = prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        ApiKey apiKey = apiKeyRepository.save(ApiKey.builder()
                .applicationId(application.getId())
                .label(label)
                .prefix(prefix)
                .lastFour(plaintext.substring(plaintext.length() - 4))
                .keyHash(encoder.encode(plaintext))
                .environment(environment)
                .expiresAt(expiresAt)
                .build());
        log.info("[API_KEY_CREATED] Clé {} créée pour l'application {}", prefix, application.getId());
        return new GeneratedApiKey(apiKey, plaintext);
    }

    public List<ApiKey> list(String applicationId) {
        applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application", applicationId));
        return apiKeyRepository.findAllByApplicationIdOrderByCreatedAtDesc(applicationId);
    }

    public ApiKey revoke(String apiKeyId) {
        ApiKey apiKey = apiKeyRepository.findById(apiKeyId)
                .orElseThrow(() -> new ResourceNotFoundException("Clé API", apiKeyId));
        if (apiKey.getRevokedAt() == null) {
            apiKey.setActive(false);
            apiKey.setRevokedAt(LocalDateTime.now());
            apiKey = apiKeyRepository.save(apiKey);
            log.info("[API_KEY_REVOKED] Clé {} révoquée", apiKey.getPrefix());
        }
        evict(apiKeyId);
        return apiKey;
    }

    /** Révoque toutes les clés d'une application (application désactivée). */
    public void evict(String apiKeyId) {
        cache.values().removeIf(entry -> entry.principal().apiKeyId().equals(apiKeyId));
    }

    /**
     * Vérifie une clé en clair. Appelé avant que le tenant soit connu : les lectures se font en
     * mode système, puis le principal porte l'organisation à utiliser pour la suite.
     */
    public Optional<ApiKeyPrincipal> authenticate(String plaintext) {
        String prefix = extractPrefix(plaintext);
        if (prefix == null) {
            return Optional.empty();
        }
        String digest = sha256(plaintext);
        Instant now = Instant.now();
        CachedAuthentication cached = cache.get(digest);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            touch(cached.principal().apiKeyId(), now);
            return Optional.of(cached.principal());
        }

        Optional<ApiKeyPrincipal> principal = TenantContext.callAsSystem(() -> verify(prefix, plaintext));
        principal.ifPresent(p -> {
            cache.put(digest, new CachedAuthentication(p, now.plus(CACHE_TTL)));
            touch(p.apiKeyId(), now);
        });
        return principal;
    }

    private Optional<ApiKeyPrincipal> verify(String prefix, String plaintext) {
        Optional<ApiKey> found = apiKeyRepository.findByPrefix(prefix);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey apiKey = found.get();
        if (!apiKey.isUsable(LocalDateTime.now()) || !encoder.matches(plaintext, apiKey.getKeyHash())) {
            log.warn("[API_KEY_REJECTED] Clé {} invalide, révoquée ou expirée", prefix);
            return Optional.empty();
        }
        boolean applicationActive = applicationRepository.findById(apiKey.getApplicationId())
                .map(a -> Boolean.TRUE.equals(a.getActive()))
                .orElse(false);
        boolean organizationActive = organizationRepository.findById(apiKey.getOrganizationId())
                .map(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .orElse(false);
        if (!applicationActive || !organizationActive) {
            log.warn("[API_KEY_REJECTED] Application ou organisation inactive pour la clé {}", prefix);
            return Optional.empty();
        }
        return Optional.of(new ApiKeyPrincipal(apiKey.getId(), apiKey.getOrganizationId(),
                apiKey.getApplicationId(), apiKey.getEnvironment()));
    }

    /** {@code lastUsedAt} est mis à jour au plus une fois par minute et par clé. */
    private void touch(String apiKeyId, Instant now) {
        Instant previous = lastTouched.get(apiKeyId);
        if (previous != null && previous.plus(LAST_USED_RESOLUTION).isAfter(now)) {
            return;
        }
        lastTouched.put(apiKeyId, now);
        try {
            apiKeyRepository.touchLastUsed(apiKeyId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[API_KEY_TOUCH_FAILED] Mise à jour de lastUsedAt impossible pour {} : {}", apiKeyId, e.getMessage());
        }
    }

    static String extractPrefix(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        String envPrefix;
        if (plaintext.startsWith(EnvironmentType.LIVE.apiKeyPrefix())) {
            envPrefix = EnvironmentType.LIVE.apiKeyPrefix();
        } else if (plaintext.startsWith(EnvironmentType.TEST.apiKeyPrefix())) {
            envPrefix = EnvironmentType.TEST.apiKeyPrefix();
        } else {
            return null;
        }
        String rest = plaintext.substring(envPrefix.length());
        if (rest.length() <= ID_LENGTH + 1 || rest.charAt(ID_LENGTH) != '_') {
            return null;
        }
        return envPrefix + rest.substring(0, ID_LENGTH);
    }

    private String randomIdentifier() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CachedAuthentication(ApiKeyPrincipal principal, Instant expiresAt) {
    }
}
