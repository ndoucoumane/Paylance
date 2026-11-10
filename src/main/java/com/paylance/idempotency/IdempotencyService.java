package com.paylance.idempotency;

import com.paylance.web.exception.DuplicatePaymentRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Verrou distribué (Redis SETNX + TTL) empêchant deux requêtes d'initiation identiques
 * (même organisation/application + même clé) d'être traitées en parallèle et de créer un doublon.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final String KEY_PREFIX = "paylance:idempotency:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Tente d'acquérir le verrou. Lève {@link DuplicatePaymentRequestException} si une requête
     * identique est déjà en cours de traitement.
     *
     * @return un jeton à passer à {@link #release} une fois le traitement terminé.
     */
    public String acquireLock(String scope, String idempotencyKey) {
        String key = buildKey(scope, idempotencyKey);
        String token = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[IDEMPOTENCY_LOCK_ERROR] Erreur Redis lors de l'acquisition du verrou {} ({}ms): {}", key, duration, e.getMessage());
            throw e;
        }
        long duration = System.currentTimeMillis() - start;

        if (Boolean.FALSE.equals(acquired)) {
            log.warn("[IDEMPOTENCY_LOCK_CONFLICT] Conflit de verrou pour la clé {} ({}ms)", key, duration);
            throw new DuplicatePaymentRequestException(
                    "Une requête avec la même idempotencyKey est déjà en cours de traitement");
        }
        log.info("[IDEMPOTENCY_LOCK_ACQUIRED] Verrou Redis acquis pour {} ({}ms)", key, duration);
        return token;
    }

    public void release(String scope, String idempotencyKey, String token) {
        String key = buildKey(scope, idempotencyKey);
        long start = System.currentTimeMillis();
        try {
            String current = redisTemplate.opsForValue().get(key);
            if (token.equals(current)) {
                redisTemplate.delete(key);
            }
            long duration = System.currentTimeMillis() - start;
            log.info("[IDEMPOTENCY_LOCK_RELEASED] Verrou Redis libéré pour {} ({}ms)", key, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[IDEMPOTENCY_LOCK_RELEASE_FAILED] Erreur lors de la libération du verrou Redis {} ({}ms): {}", key, duration, e.getMessage());
        }
    }

    private String buildKey(String scope, String idempotencyKey) {
        return KEY_PREFIX + scope + ":" + idempotencyKey;
    }
}
