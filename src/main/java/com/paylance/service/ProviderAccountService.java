package com.paylance.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.provider.ConnectionTestResult;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.provider.ProviderConnection;
import com.paylance.provider.credentials.ProviderCredentials;
import com.paylance.provider.credentials.ProviderCredentialsFactory;
import com.paylance.provider.orangemoney.OrangeMoneyClient;
import com.paylance.repository.ProviderAccountRepository;
import com.paylance.security.crypto.EncryptionService;
import com.paylance.tenant.TenantContext;
import com.paylance.web.exception.BusinessRuleException;
import com.paylance.web.exception.ConflictException;
import com.paylance.web.exception.ProviderAccountNotConfiguredException;
import com.paylance.web.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Comptes marchands des clients chez les prestataires. Les identifiants sont chiffrés
 * (AES-GCM, donnée associée = organisation + champ) et ne sortent jamais de ce service
 * qu'à destination d'un client prestataire, via une {@link ProviderConnection}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderAccountService {

    static final String CONNECTION_REFUSED =
            "Connexion refusée par le prestataire. Vérifiez les identifiants saisis et l'environnement (test ou production).";

    private final ProviderAccountRepository repository;
    private final EncryptionService encryptionService;
    private final PaymentProviderRegistry providerRegistry;
    private final ObjectProvider<OrangeMoneyClient> orangeMoneyClient;
    private final ObjectMapper objectMapper;

    public ProviderAccount create(PaymentProvider provider, String label, EnvironmentType environment,
                                  Map<String, String> credentials, String webhookSecret) {
        String organizationId = TenantContext.requireOrganizationId();
        ProviderCredentials typed = validatedCredentials(provider, credentials);
        ProviderAccount account = ProviderAccount.builder()
                .provider(provider)
                .label(label)
                .environment(environment)
                .credentialsEncrypted(encryptCredentials(organizationId, typed))
                .webhookSecretEncrypted(encryptWebhookSecret(organizationId, webhookSecret))
                .status(ProviderAccountStatus.PENDING_VERIFICATION)
                .build();
        account = repository.save(account);
        log.info("[PROVIDER_ACCOUNT_CREATED] Compte {} {} ({}) créé", provider, account.getId(), environment);
        return account;
    }

    /** Champs {@code null} = inchangés. Changer les identifiants repasse le compte en vérification. */
    public ProviderAccount update(String id, String label, Map<String, String> credentials, String webhookSecret) {
        ProviderAccount account = get(id);
        if (label != null && !label.isBlank()) {
            account.setLabel(label);
        }
        if (credentials != null) {
            ProviderCredentials typed = validatedCredentials(account.getProvider(), credentials);
            account.setCredentialsEncrypted(encryptCredentials(account.getOrganizationId(), typed));
            if (account.getStatus() != ProviderAccountStatus.DISABLED) {
                account.setStatus(ProviderAccountStatus.PENDING_VERIFICATION);
            }
            evictTokens(account);
        }
        if (webhookSecret != null) {
            account.setWebhookSecretEncrypted(encryptWebhookSecret(account.getOrganizationId(), webhookSecret));
        }
        return repository.save(account);
    }

    public ProviderAccount disable(String id) {
        ProviderAccount account = get(id);
        account.setStatus(ProviderAccountStatus.DISABLED);
        evictTokens(account);
        return repository.save(account);
    }

    /** Appel en lecture seule chez le prestataire ; un succès active le compte. */
    public ProviderAccount testConnection(String id) {
        ProviderAccount account = get(id);
        if (account.getStatus() == ProviderAccountStatus.DISABLED) {
            throw new ConflictException("Ce compte prestataire est désactivé.");
        }
        ConnectionTestResult result = providerRegistry.resolve(account.getProvider()).testConnection(toConnection(account));
        account.setLastVerifiedAt(LocalDateTime.now());
        // Ce message est affiché au marchand : en cas d'échec, jamais l'erreur brute de
        // l'opérateur (URL, corps HTTP...). Le détail reste dans les journaux applicatifs.
        if (!result.success()) {
            log.warn("[PROVIDER_ACCOUNT_TEST_DETAIL] Compte {} : {}", account.getId(), result.message());
        }
        account.setLastVerificationMessage(result.success() ? truncate(result.message()) : CONNECTION_REFUSED);
        if (result.success() && account.getStatus() == ProviderAccountStatus.PENDING_VERIFICATION) {
            account.setStatus(ProviderAccountStatus.ACTIVE);
        }
        log.info("[PROVIDER_ACCOUNT_TEST] Compte {} : {}", account.getId(), result.success() ? "OK" : "ÉCHEC");
        return repository.save(account);
    }

    public ProviderAccount get(String id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Compte prestataire", id));
    }

    public List<ProviderAccount> list() {
        return repository.findAllByOrderByCreatedAtAsc();
    }

    /**
     * Compte qui encaissera un nouveau paiement : celui demandé explicitement, sinon l'unique
     * compte ACTIVE pour ce prestataire (et cet environnement s'il est imposé par la clé API).
     */
    public ProviderAccount selectForNewPayment(PaymentProvider provider, EnvironmentType environment, String requestedId) {
        ProviderAccount account;
        if (requestedId != null && !requestedId.isBlank()) {
            account = repository.findById(requestedId)
                    .orElseThrow(() -> new ProviderAccountNotConfiguredException("Compte prestataire introuvable : " + requestedId));
            if (account.getProvider() != provider) {
                throw new ProviderAccountNotConfiguredException("Le compte " + requestedId + " n'est pas un compte " + provider);
            }
        } else {
            List<ProviderAccount> candidates = (environment != null ? List.of(environment) : List.of(EnvironmentType.values()))
                    .stream()
                    .flatMap(env -> repository.findAllByProviderAndEnvironmentAndStatus(provider, env, ProviderAccountStatus.ACTIVE).stream())
                    .toList();
            if (candidates.isEmpty()) {
                throw new ProviderAccountNotConfiguredException("Aucun compte " + provider + " actif"
                        + (environment != null ? " en " + environment : "") + " pour cette organisation");
            }
            if (candidates.size() > 1) {
                throw new ProviderAccountNotConfiguredException("Plusieurs comptes " + provider
                        + " actifs : préciser providerAccountId");
            }
            account = candidates.get(0);
        }
        if (account.getStatus() != ProviderAccountStatus.ACTIVE) {
            throw new ProviderAccountNotConfiguredException("Le compte " + account.getId() + " n'est pas actif ("
                    + account.getStatus() + ") : tester la connexion d'abord");
        }
        if (environment != null && account.getEnvironment() != environment) {
            throw new ProviderAccountNotConfiguredException("Le compte " + account.getId() + " est en "
                    + account.getEnvironment() + ", la clé API en " + environment);
        }
        return account;
    }

    public ProviderConnection toConnection(ProviderAccount account) {
        String json = encryptionService.decrypt(account.getCredentialsEncrypted(), credentialsAad(account.getOrganizationId()));
        ProviderCredentials credentials = ProviderCredentialsFactory.from(account.getProvider(), readMap(json));
        String webhookSecret = account.getWebhookSecretEncrypted() == null ? null
                : encryptionService.decrypt(account.getWebhookSecretEncrypted(), webhookSecretAad(account.getOrganizationId()));
        return new ProviderConnection(account.getId(), account.getProvider(), account.getEnvironment(), credentials, webhookSecret);
    }

    public Map<String, String> maskedCredentials(ProviderAccount account) {
        return toConnection(account).credentials().masked();
    }

    public boolean hasWebhookSecret(ProviderAccount account) {
        return account.getWebhookSecretEncrypted() != null;
    }

    /** Saisie du marchand : une erreur d'identifiants est une règle métier (422), message affichable. */
    private static ProviderCredentials validatedCredentials(PaymentProvider provider, Map<String, String> credentials) {
        try {
            return ProviderCredentialsFactory.from(provider, credentials);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(e.getMessage());
        }
    }

    private String encryptCredentials(String organizationId, ProviderCredentials credentials) {
        try {
            return encryptionService.encrypt(objectMapper.writeValueAsString(credentials.toMap()), credentialsAad(organizationId));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String encryptWebhookSecret(String organizationId, String webhookSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return null;
        }
        return encryptionService.encrypt(webhookSecret, webhookSecretAad(organizationId));
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Identifiants déchiffrés illisibles", e);
        }
    }

    private void evictTokens(ProviderAccount account) {
        if (account.getProvider() == PaymentProvider.ORANGE_MONEY) {
            orangeMoneyClient.ifAvailable(client -> client.evictToken(account.getId()));
        }
    }

    static String credentialsAad(String organizationId) {
        return organizationId + ":provider_account.credentials";
    }

    static String webhookSecretAad(String organizationId) {
        return organizationId + ":provider_account.webhook_secret";
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
