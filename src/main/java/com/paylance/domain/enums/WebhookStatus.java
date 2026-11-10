package com.paylance.domain.enums;

/**
 * Statut de traitement d'un événement webhook reçu d'un provider.
 * Permet de rendre le traitement des webhooks idempotent : les providers
 * peuvent renvoyer plusieurs fois le même événement.
 */
public enum WebhookStatus {
    RECEIVED,
    PROCESSED,
    IGNORED_DUPLICATE,
    INVALID_SIGNATURE,
    FAILED
}
