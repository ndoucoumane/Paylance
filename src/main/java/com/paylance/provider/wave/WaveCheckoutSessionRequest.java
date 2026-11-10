package com.paylance.provider.wave;

import com.fasterxml.jackson.annotation.JsonProperty;

record WaveCheckoutSessionRequest(
        String amount,
        String currency,
        @JsonProperty("client_reference") String clientReference,
        @JsonProperty("success_url") String successUrl,
        @JsonProperty("error_url") String errorUrl
) {
}
