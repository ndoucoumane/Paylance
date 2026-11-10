package com.kebpay.provider.wave;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
record WavePayoutRequest(
        @JsonProperty("receive_amount") String receiveAmount,
        String currency,
        String mobile,
        String name,
        @JsonProperty("client_reference") String clientReference
) {
}
