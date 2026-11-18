package com.paylance.web;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.security.KeycloakRoleConverter;
import com.paylance.security.OrganizationAccess;
import com.paylance.support.AbstractApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Points de conformité remontés par le front (formats, messages, CORS, recherche, dates). */
class FrontContractIT extends AbstractApiIT {

    private static final String ORG = OrganizationAccess.HEADER;
    private static final String FRONT_ORIGIN = "http://localhost:5173";

    private Organization org;
    private ProviderAccount omAccount;
    private String admin;

    @BeforeEach
    void setUp() {
        org = createOrganization("Boutique Teranga");
        omAccount = createActiveOrangeMoneyAccount(org.getId());
        admin = newSub();
        addMember(org.getId(), admin, MemberRole.ADMIN);
    }

    // --- CORS ------------------------------------------------------------------------------

    @Test
    void cors_allowsFrontDevOriginWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/dashboard/payments")
                        .header(HttpHeaders.ORIGIN, FRONT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-organization-id,accept"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void cors_rejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/dashboard/payments")
                        .header(HttpHeaders.ORIGIN, "https://site-malveillant.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    // --- Recherche q -----------------------------------------------------------------------

    @Test
    void search_ignoresSpaces_andTreatsWildcardsLiterally() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        inTenant(org.getId(), () -> {
            Payment p = paymentRepository.findById(payment.getId()).orElseThrow();
            p.setPayerPhone("+221771234567");
            return paymentRepository.save(p);
        });

        mockMvc.perform(get("/api/v1/dashboard/payments").param("q", "77 123")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].paymentId", hasItem(payment.getId())));

        // « % » seul ne doit pas tout renvoyer : il est cherché littéralement.
        mockMvc.perform(get("/api/v1/dashboard/payments").param("q", "%")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.content[*].paymentId", not(hasItem(payment.getId()))));
    }

    // --- refundableAmount et champs nuls --------------------------------------------------

    @Test
    void refundableAmount_isComputedByServer_inListAndDetail() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        when(provider.refund(any(), any(), anyLong(), anyString()))
                .thenReturn(new ProviderRefundResult("rf-1", RefundStatus.SUCCESS, "{}", null));
        mockMvc.perform(post("/api/v1/dashboard/payments/" + payment.getId() + "/refunds")
                        .with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":4000,\"reason\":\"Retour\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dashboard/payments/" + payment.getId()).with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundableAmount").value(6000))
                // Champ nul présent (null), pas omis.
                .andExpect(jsonPath("$.failureReason").hasJsonPath())
                .andExpect(jsonPath("$.failureReason").isEmpty());

        mockMvc.perform(get("/api/v1/dashboard/payments").with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.content[?(@.paymentId == '" + payment.getId() + "')].refundableAmount").value(6000));
    }

    @Test
    void refundableAmount_isZeroWhenNotRefundable() throws Exception {
        Payment pending = createPayment(org.getId(), null, createActiveWaveAccount(org.getId(), EnvironmentType.LIVE).getId(),
                PaymentStatus.PENDING, 15_000);

        mockMvc.perform(get("/api/v1/dashboard/payments/" + pending.getId()).with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.refundableAmount").value(0));
    }

    @Test
    void requestedBy_isTheDisplayName() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        when(provider.refund(any(), any(), anyLong(), anyString()))
                .thenReturn(new ProviderRefundResult("rf-1", RefundStatus.SUCCESS, "{}", null));

        mockMvc.perform(post("/api/v1/dashboard/payments/" + payment.getId() + "/refunds")
                        .with(jwt().jwt(j -> j.subject(admin).claim("given_name", "Awa").claim("family_name", "Diop")
                                        .claim("email", "awa@exemple.sn").claim("email_verified", true)
                                        .claim("realm_access", Map.of("roles", List.of())))
                                .authorities(new KeycloakRoleConverter()))
                        .header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Retour\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedBy").value("Awa Diop"));

        String auditUser = jdbcTemplate.queryForObject(
                "SELECT requested_by_user_id FROM refunds WHERE payment_id = ?", String.class, payment.getId());
        assertThat(auditUser).isEqualTo(admin);
    }

    // --- Messages d'erreur -----------------------------------------------------------------

    @Test
    void validationError_explainsWhatToFix() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);

        mockMvc.perform(post("/api/v1/dashboard/payments/" + payment.getId() + "/refunds")
                        .with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("Indiquez le motif du remboursement."),
                        containsString("Le montant du remboursement doit être supérieur à 0."))))
                .andExpect(jsonPath("$.timestamp").value(endsWith("Z")));
    }

    @Test
    void forbidden_messagesAreExplicit() throws Exception {
        String viewer = newSub();
        addMember(org.getId(), viewer, MemberRole.VIEWER);
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);

        mockMvc.perform(post("/api/v1/dashboard/payments/" + payment.getId() + "/refunds")
                        .with(user(viewer, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Vous n'avez pas les droits nécessaires pour cette action."));

        mockMvc.perform(get("/api/v1/organizations").with(user(newSub(), false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Vérifiez votre adresse e-mail pour accéder à cet espace."));
    }

    @Test
    void unknownCredentialKey_isUnprocessable() throws Exception {
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts").with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"provider":"WAVE","label":"Wave","environment":"TEST",
                                 "credentials":{"checkoutApiKey":"k-1234","privateKey":"x"}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Identifiant non reconnu pour ce prestataire : privateKey."));
    }

    // --- Dates, pagination, tri ------------------------------------------------------------

    @Test
    void fromParameter_acceptsUtcWithOrWithoutZone() throws Exception {
        Payment payment = createOrangeMoneyPayment(org.getId(), omAccount.getId(), 10_000);
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant inOneHour = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String hourAgoWithoutZone = LocalDateTime.ofInstant(hourAgo, ZoneOffset.UTC).toString();

        for (String from : new String[]{hourAgo.toString(), hourAgoWithoutZone}) {
            mockMvc.perform(get("/api/v1/dashboard/payments").param("from", from)
                            .with(user(admin, true)).header(ORG, org.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].paymentId", hasItem(payment.getId())));
        }
        mockMvc.perform(get("/api/v1/dashboard/payments").param("from", inOneHour.toString())
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.content[*].paymentId", not(hasItem(payment.getId()))));
    }

    @Test
    void invalidDate_isBadRequestWithReadableMessage() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/payments").param("from", "hier")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le paramètre « from » est invalide."));
    }

    @Test
    void pageSize_isCappedAt1000_andSortIsWhitelisted() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/payments").param("size", "5000")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1000));

        mockMvc.perform(get("/api/v1/dashboard/payments").param("sort", "amount,desc")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/dashboard/payments").param("sort", "providerRawResponse,asc")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isBadRequest());
    }
}
