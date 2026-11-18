package com.paylance.security;

import com.paylance.domain.entity.Organization;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.MemberRole;
import com.paylance.support.AbstractApiIT;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/me}, version minimale (contrat front §2.2). */
class MeIT extends AbstractApiIT {

    @Test
    void withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void identityComesFromToken() throws Exception {
        String sub = newSub();
        mockMvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(sub)
                                .claim("given_name", "Awa").claim("family_name", "Diop")
                                .claim("email", "awa.diop@exemple.sn").claim("email_verified", true)
                                .claim("realm_access", Map.of("roles", List.of())))
                        .authorities(new KeycloakRoleConverter())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.user.id").value(sub))
                .andExpect(jsonPath("$.user.firstName").value("Awa"))
                .andExpect(jsonPath("$.user.lastName").value("Diop"))
                .andExpect(jsonPath("$.user.email").value("awa.diop@exemple.sn"))
                .andExpect(jsonPath("$.user.emailVerified").value(true));
    }

    @Test
    void unverifiedEmail_isReported_andOrganizationIsHidden() throws Exception {
        String sub = newSub();
        Organization org = createOrganization("Boutique");
        addMember(org.getId(), sub, MemberRole.OWNER);

        mockMvc.perform(get("/api/v1/me").with(user(sub, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(false))
                .andExpect(jsonPath("$.organization").isEmpty())
                .andExpect(jsonPath("$.progress.currentStep").value("COMPANY"));
    }

    @Test
    void newUser_startsAtCompanyStep() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").with(user(newSub(), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.currentStep").value("COMPANY"))
                .andExpect(jsonPath("$.progress.completedSteps").isEmpty())
                .andExpect(jsonPath("$.progress.providersSkipped").value(false))
                .andReturn();
        // Le front teste organization === null : le champ doit être présent.
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).contains("\"organization\":null");
    }

    @Test
    void member_isDone_withRoleAndProvidersSkippedWithoutActiveAccount() throws Exception {
        String sub = newSub();
        Organization org = createOrganization("Boutique Teranga");
        addMember(org.getId(), sub, MemberRole.ADMIN);

        mockMvc.perform(get("/api/v1/me").with(user(sub, true)))
                .andExpect(jsonPath("$.organization.id").value(org.getId()))
                .andExpect(jsonPath("$.organization.role").value("ADMIN"))
                .andExpect(jsonPath("$.organization.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.progress.currentStep").value("DONE"))
                .andExpect(jsonPath("$.progress.completedSteps", contains("COMPANY", "PREFERENCES", "PLAN", "PROVIDERS", "DONE")))
                .andExpect(jsonPath("$.progress.providersSkipped").value(true));

        createActiveWaveAccount(org.getId(), EnvironmentType.LIVE);
        mockMvc.perform(get("/api/v1/me").with(user(sub, true)))
                .andExpect(jsonPath("$.progress.providersSkipped").value(false));
    }

    @Test
    void firstOrganization_isTheSameAsInOrganizationList() throws Exception {
        String sub = newSub();
        Organization first = createOrganization("Première");
        Organization second = createOrganization("Seconde");
        addMember(first.getId(), sub, MemberRole.OWNER);
        addMember(second.getId(), sub, MemberRole.VIEWER);

        mockMvc.perform(get("/api/v1/organizations").with(user(sub, true)))
                .andExpect(jsonPath("$[0].id").value(first.getId()));
        mockMvc.perform(get("/api/v1/me").with(user(sub, true)))
                .andExpect(jsonPath("$.organization.id").value(first.getId()))
                .andExpect(jsonPath("$.organization.role").value("OWNER"));
    }
}
