package com.paylance.repository;

import com.paylance.domain.entity.WebhookEvent;
import com.paylance.domain.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    Optional<WebhookEvent> findByProviderAndProviderAccountIdAndProviderEventId(
            PaymentProvider provider, String providerAccountId, String providerEventId);
}
