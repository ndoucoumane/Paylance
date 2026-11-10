package com.kebpay.web.exception;

/**
 * Erreur de communication avec un provider de paiement (timeout, réponse invalide, HTTP 5xx...).
 */
public class ProviderCommunicationException extends RuntimeException {
    public ProviderCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderCommunicationException(String message) {
        super(message);
    }
}
