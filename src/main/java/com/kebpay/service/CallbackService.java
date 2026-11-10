package com.kebpay.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Point d'entrée asynchrone déclenché après commit d'une transition de statut terminale.
 * Délègue l'appel HTTP (avec retry) à {@link PlatformCallbackSender}, un bean distinct :
 * l'invocation via un autre bean garantit que le proxy Spring Retry est bien traversé
 * (un appel interne à la même classe contournerait l'AOP).
 */
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final PlatformCallbackSender platformCallbackSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        platformCallbackSender.send(event.paymentId());
    }
}
