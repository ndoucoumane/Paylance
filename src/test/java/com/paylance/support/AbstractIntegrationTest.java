package com.paylance.support;

import com.paylance.domain.entity.ApiKey;
import com.paylance.domain.entity.Application;
import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.*;
import com.paylance.repository.ApplicationRepository;
import com.paylance.repository.OrganizationRepository;
import com.paylance.repository.PaymentRepository;
import com.paylance.repository.ProviderAccountRepository;
import com.paylance.security.apikey.ApiKeyService;
import com.paylance.security.crypto.EncryptionService;
import com.paylance.service.ProviderAccountService;
import com.paylance.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base des tests d'intégration : PostgreSQL 16 et Redis 7 réels (Testcontainers), démarrés une
 * fois pour toute la suite. Aucun appel à un vrai prestataire : les clients Wave / Orange Money
 * sont remplacés par des doubles dans chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final String MASTER_KEY;

    static {
        POSTGRES.start();
        REDIS.start();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        MASTER_KEY = Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("paylance.security.encryption-master-key", () -> MASTER_KEY);
        registry.add("paylance.scheduling.enabled", () -> "false");
    }

    @Autowired protected OrganizationRepository organizationRepository;
    @Autowired protected ApplicationRepository applicationRepository;
    @Autowired protected ProviderAccountRepository providerAccountRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected ProviderAccountService providerAccountService;
    @Autowired protected ApiKeyService apiKeyService;
    @Autowired protected EncryptionService encryptionService;
    @Autowired protected JdbcTemplate jdbcTemplate;

    protected static <T> T inTenant(String organizationId, Supplier<T> action) {
        return TenantContext.callAs(organizationId, action);
    }

    protected Organization createOrganization(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizationRepository.save(Organization.builder().name(name).slug(slug).country("SN").build());
    }

    protected Application createApplication(String organizationId, EnvironmentType environment) {
        return inTenant(organizationId, () -> applicationRepository.save(Application.builder()
                .name("Boutique en ligne")
                .slug("boutique-" + UUID.randomUUID().toString().substring(0, 8))
                .environment(environment)
                .callbackUrl("https://merchant.example/callback")
                .successUrl("https://merchant.example/ok")
                .errorUrl("https://merchant.example/ko")
                .signingSecretEncrypted(encryptionService.encrypt("whsec_test_" + UUID.randomUUID(),
                        organizationId + ":application.signing_secret"))
                .build()));
    }

    protected ProviderAccount createActiveWaveAccount(String organizationId, EnvironmentType environment) {
        return inTenant(organizationId, () -> {
            ProviderAccount account = providerAccountService.create(PaymentProvider.WAVE, "Compte Wave", environment,
                    Map.of("checkoutApiKey", "wave_test_fake_" + UUID.randomUUID()), "wave-webhook-secret-test");
            account.setStatus(ProviderAccountStatus.ACTIVE);
            return providerAccountRepository.save(account);
        });
    }

    protected String createApiKey(String organizationId, String applicationId) {
        return inTenant(organizationId, () -> apiKeyService.create(applicationId, "test", null).plaintext());
    }

    protected Payment createPayment(String organizationId, String applicationId, String providerAccountId,
                                    PaymentStatus status, long amount) {
        return inTenant(organizationId, () -> paymentRepository.save(Payment.builder()
                .applicationId(applicationId)
                .providerAccountId(providerAccountId)
                .environment(EnvironmentType.LIVE)
                .paymentNumber("PL-TEST-" + UUID.randomUUID().toString().substring(0, 12))
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .provider(PaymentProvider.WAVE)
                .providerTransactionId("cos-" + UUID.randomUUID())
                .amount(amount)
                .currency("XOF")
                .payerPhone("+221770000000")
                .status(status)
                .paidAt(status == PaymentStatus.SUCCESS ? LocalDateTime.now() : null)
                .build()));
    }
}
