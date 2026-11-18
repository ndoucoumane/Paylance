package com.paylance.provider.wave;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WaveBalanceResponse(
        String amount,
        String currency
) {
}
