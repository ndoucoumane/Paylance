package com.paylance.repository;

import com.paylance.domain.entity.ProviderAccount;
import com.paylance.domain.enums.EnvironmentType;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ProviderAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, String> {

    List<ProviderAccount> findAllByProviderAndEnvironmentAndStatus(
            PaymentProvider provider, EnvironmentType environment, ProviderAccountStatus status);

    List<ProviderAccount> findAllByOrderByCreatedAtAsc();
}
