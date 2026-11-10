package com.paylance.repository;

import com.paylance.domain.entity.PlatformApiKey;
import com.paylance.domain.enums.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformApiKeyRepository extends JpaRepository<PlatformApiKey, String> {

    Optional<PlatformApiKey> findByPlatformAndIsActiveTrue(Platform platform);
}
