package com.kebpay.provider.wave;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record WaveCheckoutSessionResponse(
        String id,
        @JsonProperty("wave_launch_url") String waveLaunchUrl,
        @JsonProperty("checkout_status") String checkoutStatus,
        @JsonProperty("payment_status") String paymentStatus,
        @JsonProperty("client_reference") String clientReference,
        String amount,
        String currency
) {
}
