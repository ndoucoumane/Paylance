package com.paylance.service;

import com.paylance.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Point d'entrée asynchrone déclenché après commit d'une transition de statut terminale.
 * Délègue l'appel HTTP (avec retry) à {@link ApplicationCallbackSender}, un bean distinct :
 * l'invocation via un autre bean garantit que le proxy Spring Retry est bien traversé
 * (un appel interne à la même classe contournerait l'AOP).
 */
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final ApplicationCallbackSender callbackSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        // Thread asynchrone : le tenant n'est pas hérité, on le rétablit depuis l'événement.
        TenantContext.runAs(event.organizationId(), () -> callbackSender.send(event.paymentId()));
    }
}
