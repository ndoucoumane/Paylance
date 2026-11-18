package com.paylance.repository;

import com.paylance.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    /** À appeler en mode système (authentification, avant que le tenant soit connu). */
    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findAllByApplicationIdOrderByCreatedAtDesc(String applicationId);

    /** Mise à jour légère, sans charger l'entité ni toucher à la version. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE api_keys SET last_used_at = :usedAt WHERE id = :id", nativeQuery = true)
    void touchLastUsed(@Param("id") String id, @Param("usedAt") LocalDateTime usedAt);
}
