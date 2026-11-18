package com.paylance.web.exception;

public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
