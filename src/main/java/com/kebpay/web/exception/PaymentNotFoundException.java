package com.kebpay.web.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String paymentId) {
        super("Paiement introuvable : " + paymentId);
    }
}
