package com.kebpay.provider.wave;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record WavePayoutResponse(
        String id,
        String amount,
        @JsonProperty("receive_amount") String receiveAmount,
        String currency,
        String mobile,
        String status,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("payout_error") String payoutError
) {
}
