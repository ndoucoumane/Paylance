package com.paylance.web.exception;

/**
 * Levée quand une requête d'initiation identique (même plateforme + idempotencyKey)
 * est déjà en cours de traitement (verrou Redis) ou existe déjà en base.
 */
public class DuplicatePaymentRequestException extends RuntimeException {
    public DuplicatePaymentRequestException(String message) {
        super(message);
    }
}
