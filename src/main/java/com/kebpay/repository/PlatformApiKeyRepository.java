package com.kebpay.repository;

import com.kebpay.domain.entity.PlatformApiKey;
import com.kebpay.domain.enums.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformApiKeyRepository extends JpaRepository<PlatformApiKey, String> {

    Optional<PlatformApiKey> findByPlatformAndIsActiveTrue(Platform platform);
}
