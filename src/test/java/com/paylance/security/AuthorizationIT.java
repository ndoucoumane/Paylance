package com.paylance.security;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.domain.enums.RefundStatus;
import com.paylance.provider.ConnectionTestResult;
import com.paylance.provider.ProviderRefundResult;
import com.paylance.support.AbstractApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Matrice des droits (docs/contrat-api-securite.md §2), à travers la vraie chaîne de sécurité. */
class AuthorizationIT extends AbstractApiIT {

    private static final String ORG = OrganizationAccess.HEADER;
    private static final String WAVE_ACCOUNT = """
            {"provider":"WAVE","label":"Wave boutique","environment":"TEST",
             "credentials":{"checkoutApiKey":"wave_sn_prod_secretvalue_a1b2"},"webhookSecret":"whsec_value"}""";

    private Organization org;
    private ProviderAccount waveAccount;
    private Payment payment;
    private String owner;
    private String admin;
    private String developer;
    private String viewer;

    @BeforeEach
    void setUp() {
        org = createOrganization("Boutique Teranga");
        waveAccount = createActiveWaveAccount(org.getId(), EnvironmentType.LIVE);
        payment = createPayment(org.getId(), null, waveAccount.getId(), PaymentStatus.SUCCESS, 15_000);
        owner = newSub();
        admin = newSub();
        developer = newSub();
        viewer = newSub();
        addMember(org.getId(), owner, MemberRole.OWNER);
        addMember(org.getId(), admin, MemberRole.ADMIN);
        addMember(org.getId(), developer, MemberRole.DEVELOPER);
        addMember(org.getId(), viewer, MemberRole.VIEWER);
    }

    private String refundsUrl() {
        return "/api/v1/dashboard/payments/" + payment.getId() + "/refunds";
    }

    // --- Authentification et e-mail vérifié -------------------------------------------------

    @Test
    void withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/organizations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/dashboard/payments").header(ORG, org.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedEmail_cannotCreateOrganization() throws Exception {
        mockMvc.perform(post("/api/v1/organizations").with(user(newSub(), false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Boutique\",\"country\":\"SN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unverifiedEmail_cannotUseDashboard_evenAsOwner() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/payments").with(user(owner, false)).header(ORG, org.getId()))
                .andExpect(status().isForbidden());
    }

    // --- Organisations ---------------------------------------------------------------------

    @Test
    void verifiedUser_createsOrganization_andBecomesOwner() throws Exception {
        String sub = newSub();
        mockMvc.perform(post("/api/v1/organizations").with(user(sub, true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Boutique Teranga\",\"country\":\"SN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.defaultCurrency").value("XOF"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")));

        mockMvc.perform(get("/api/v1/organizations").with(user(sub, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
    }

    @Test
    void unsupportedCountry_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/organizations").with(user(newSub(), true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Boutique\",\"country\":\"US\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nonMember_isForbiddenOnOrganization() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/payments").with(user(newSub(), true)).header(ORG, org.getId()))
                .andExpect(status().isForbidden());
    }

    // --- Rôles Keycloak : liste blanche ----------------------------------------------------

    @Test
    void realmRoleNamedLikeAnOrgRole_grantsNothing() throws Exception {
        // Un VIEWER dont le jeton porte un rôle realm « org_owner » reste VIEWER.
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts").with(user(viewer, true, "org_owner", "ORG_ADMIN"))
                        .header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(WAVE_ACCOUNT))
                .andExpect(status().isForbidden());
    }

    // --- Mouvements d'argent : OWNER / ADMIN uniquement --------------------------------------

    @Test
    void developerAndViewer_cannotRefund() throws Exception {
        for (String sub : new String[]{developer, viewer}) {
            mockMvc.perform(post(refundsUrl()).with(user(sub, true)).header(ORG, org.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void developerAndViewer_cannotScheduleOrCancelRefunds() throws Exception {
        for (String sub : new String[]{developer, viewer}) {
            mockMvc.perform(post("/api/v1/dashboard/payments/" + payment.getId() + "/scheduled-refunds")
                            .with(user(sub, true)).header(ORG, org.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/dashboard/scheduled-refunds/sch-1/cancel")
                            .with(user(sub, true)).header(ORG, org.getId()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void developer_cannotCreateDashboardPayment() throws Exception {
        mockMvc.perform(post("/api/v1/dashboard/payments").with(user(developer, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_canRefund_andWavePartialIsRejected() throws Exception {
        when(provider.refund(any(), any(), anyLong(), anyString()))
                .thenReturn(new ProviderRefundResult("rf-1", RefundStatus.SUCCESS, "{}", null));

        mockMvc.perform(post(refundsUrl()).with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000,\"reason\":\"Commande annulée\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Wave ne permet que le remboursement total d'un paiement."));

        mockMvc.perform(post(refundsUrl()).with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Commande annulée\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(15000));

        mockMvc.perform(post(refundsUrl()).with(user(admin, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Encore\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Impossible de rembourser un paiement au statut remboursé."));
    }

    // --- Comptes prestataires --------------------------------------------------------------

    @Test
    void admin_createsProviderAccount_secretsAreNeverReturned() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/dashboard/provider-accounts").with(user(admin, true))
                        .header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(WAVE_ACCOUNT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.credentials.checkoutApiKey").value("••••a1b2"))
                .andExpect(jsonPath("$.webhookSecretConfigured").value(true))
                .andExpect(jsonPath("$.webhookUrl").value(containsString("/api/v1/webhooks/wave/")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secretvalue").doesNotContain("whsec_value");
    }

    @Test
    void unknownCredentialKey_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts").with(user(owner, true)).header(ORG, org.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"provider":"WAVE","label":"Wave","environment":"TEST",
                                 "credentials":{"checkoutApiKey":"k-1234","privateKey":"x"}}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void viewer_listsAccountsWithMaskedSecrets_butCannotTestOrDisable() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/provider-accounts").with(user(viewer, true)).header(ORG, org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].credentials.checkoutApiKey").value(startsWith("••••")));
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + waveAccount.getId() + "/test")
                        .with(user(viewer, true)).header(ORG, org.getId()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + waveAccount.getId() + "/disable")
                        .with(user(viewer, true)).header(ORG, org.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void failedConnectionTest_doesNotLeakProviderError() throws Exception {
        when(provider.testConnection(any()))
                .thenReturn(new ConnectionTestResult(false, "401 Unauthorized from GET https://api.wave.com/v1/balance"));

        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + waveAccount.getId() + "/test")
                        .with(user(admin, true)).header(ORG, org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastVerificationMessage").value(startsWith("Connexion refusée")));
    }

    @Test
    void disabledAccount_isHiddenFromList_andCannotBeTested() throws Exception {
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + waveAccount.getId() + "/disable")
                        .with(user(owner, true)).header(ORG, org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(get("/api/v1/dashboard/provider-accounts").with(user(owner, true)).header(ORG, org.getId()))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + waveAccount.getId() + "/test")
                        .with(user(owner, true)).header(ORG, org.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void accountOfAnotherOrganization_isNotFound() throws Exception {
        Organization other = createOrganization("Autre");
        ProviderAccount foreign = createActiveWaveAccount(other.getId(), EnvironmentType.LIVE);

        mockMvc.perform(post("/api/v1/dashboard/provider-accounts/" + foreign.getId() + "/disable")
                        .with(user(owner, true)).header(ORG, org.getId()))
                .andExpect(status().isNotFound());
    }
}
