package com.kebpay.repository;

import com.kebpay.domain.entity.WebhookEvent;
import com.kebpay.domain.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);
}
