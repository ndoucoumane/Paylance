package com.paylance.support;

import com.paylance.domain.entity.OrganizationMember;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.ProviderAccountStatus;
import com.paylance.provider.PaymentProviderClient;
import com.paylance.provider.PaymentProviderRegistry;
import com.paylance.repository.OrganizationMemberRepository;
import com.paylance.security.KeycloakRoleConverter;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Tests HTTP de bout en bout : vraie chaîne de sécurité, vraie base. Le prestataire est un
 * double ({@link #provider}) : aucun appel réseau.
 */
@AutoConfigureMockMvc
public abstract class AbstractApiIT extends AbstractIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected OrganizationMemberRepository memberRepository;
    @MockBean protected PaymentProviderRegistry providerRegistry;

    protected PaymentProviderClient provider;

    @BeforeEach
    void stubProvider() {
        provider = Mockito.mock(PaymentProviderClient.class);
        when(providerRegistry.resolve(any())).thenReturn(provider);
    }

    /** Jeton Keycloak simulé, converti par le même KeycloakRoleConverter qu'en production. */
    protected static RequestPostProcessor user(String sub, boolean emailVerified, String... realmRoles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("email", sub + "@exemple.sn")
                        .claim("email_verified", emailVerified)
                        .claim("realm_access", Map.of("roles", List.of(realmRoles))))
                .authorities(new KeycloakRoleConverter());
    }

    protected String newSub() {
        return "kc-" + UUID.randomUUID();
    }

    protected void addMember(String organizationId, String sub, MemberRole role) {
        inTenant(organizationId, () -> memberRepository.save(OrganizationMember.builder()
                .keycloakUserId(sub).email(sub + "@exemple.sn").role(role).build()));
    }

    protected ProviderAccount createActiveOrangeMoneyAccount(String organizationId) {
        return inTenant(organizationId, () -> {
            ProviderAccount account = providerAccountService.create(PaymentProvider.ORANGE_MONEY, "Compte OM",
                    EnvironmentType.LIVE, Map.of("clientId", "om-client", "clientSecret", "om-secret-" + UUID.randomUUID(),
                            "merchantCode", "123456"), null);
            account.setStatus(ProviderAccountStatus.ACTIVE);
            return providerAccountRepository.save(account);
        });
    }

    protected Payment createOrangeMoneyPayment(String organizationId, String providerAccountId, long amount) {
        return inTenant(organizationId, () -> paymentRepository.save(Payment.builder()
                .providerAccountId(providerAccountId)
                .environment(EnvironmentType.LIVE)
                .paymentNumber("PL-TEST-" + UUID.randomUUID().toString().substring(0, 12))
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .provider(PaymentProvider.ORANGE_MONEY)
                .providerTransactionId("om-" + UUID.randomUUID())
                .amount(amount)
                .currency("XOF")
                .payerPhone("+221770000000")
                .status(PaymentStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .build()));
    }
}
