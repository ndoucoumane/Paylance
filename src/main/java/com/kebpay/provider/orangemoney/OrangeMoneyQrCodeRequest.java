package com.kebpay.provider.orangemoney;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

record OrangeMoneyQrCodeRequest(
        Money amount,
        @JsonProperty("callbackCancelUrl") String callbackCancelUrl,
        // "Succes" (une seule "s") : orthographe telle quelle côté API Orange/Sonatel, pas une faute de notre part.
        @JsonProperty("callbackSuccesUrl") String callbackSuccessUrl,
        String code,
        Map<String, Object> metadata,
        String name,
        String reference,
        String validity
) {
    record Money(String unit, String value) {
    }
}
