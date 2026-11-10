package com.paylance.web.exception;

public class ScheduledPaymentNotFoundException extends RuntimeException {
    public ScheduledPaymentNotFoundException(String scheduledPaymentId) {
        super("Paiement programmé introuvable : " + scheduledPaymentId);
    }
}
