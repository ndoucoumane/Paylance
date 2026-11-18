package com.paylance.tenant;

import com.paylance.domain.entity.Application;
import com.paylance.domain.entity.Organization;
import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentStatus;
import com.paylance.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolation au niveau persistance : filtre @TenantId + garde sur le chargement par clé. */
class TenantIsolationIT extends AbstractIntegrationTest {

    private Organization orgA;
    private Organization orgB;
    private Payment paymentA;
    private Payment paymentB;

    @BeforeEach
    void setUp() {
        orgA = createOrganization("Org A");
        orgB = createOrganization("Org B");
        Application appA = createApplication(orgA.getId(), EnvironmentType.LIVE);
        Application appB = createApplication(orgB.getId(), EnvironmentType.LIVE);
        ProviderAccount accountA = createActiveWaveAccount(orgA.getId(), EnvironmentType.LIVE);
        ProviderAccount accountB = createActiveWaveAccount(orgB.getId(), EnvironmentType.LIVE);
        paymentA = createPayment(orgA.getId(), appA.getId(), accountA.getId(), PaymentStatus.SUCCESS, 10_000);
        paymentB = createPayment(orgB.getId(), appB.getId(), accountB.getId(), PaymentStatus.SUCCESS, 20_000);
    }

    @Test
    void insert_receivesCurrentOrganizationAutomatically() {
        assertThat(paymentA.getOrganizationId()).isEqualTo(orgA.getId());
        String stored = jdbcTemplate.queryForObject("SELECT organization_id FROM payments WHERE id = ?", String.class, paymentB.getId());
        assertThat(stored).isEqualTo(orgB.getId());
    }

    @Test
    void queries_onlyReturnRowsOfCurrentTenant() {
        List<Payment> visibleToA = inTenant(orgA.getId(), () -> paymentRepository.findAll());
        assertThat(visibleToA).extracting(Payment::getOrganizationId).containsOnly(orgA.getId());
        assertThat(visibleToA).extracting(Payment::getId).contains(paymentA.getId()).doesNotContain(paymentB.getId());

        assertThat(inTenant(orgA.getId(), () -> paymentRepository.findByPaymentNumber(paymentB.getPaymentNumber()))).isEmpty();
    }

    @Test
    void loadByKey_ofAnotherTenant_isRejected() {
        assertThatThrownBy(() -> inTenant(orgA.getId(), () -> paymentRepository.findById(paymentB.getId())))
                .isInstanceOf(TenantAccessViolationException.class);
        assertThat(inTenant(orgB.getId(), () -> paymentRepository.findById(paymentB.getId()))).isPresent();
    }

    @Test
    void withoutTenant_nothingIsVisible() {
        assertThat(paymentRepository.findAll()).isEmpty();
        assertThat(applicationRepository.findAll()).isEmpty();
    }

    @Test
    void systemMode_seesAllTenants() {
        List<Payment> all = TenantContext.callAsSystem(() -> paymentRepository.findAll());
        assertThat(all).extracting(Payment::getId).contains(paymentA.getId(), paymentB.getId());
    }

    @Test
    void writingARowOfAnotherTenant_isRejected() {
        Payment foreign = TenantContext.callAsSystem(() -> paymentRepository.findById(paymentB.getId())).orElseThrow();
        foreign.setFailureReason("tentative");
        assertThatThrownBy(() -> inTenant(orgA.getId(), () -> paymentRepository.save(foreign)))
                .isInstanceOf(RuntimeException.class);
        String reason = jdbcTemplate.queryForObject("SELECT failure_reason FROM payments WHERE id = ?", String.class, paymentB.getId());
        assertThat(reason).isNull();
    }
}
