package com.kebpay.provider.orangemoney;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
record OrangeMoneyQrCodeResponse(
        String deepLink,
        String qrcode,
        Map<String, Object> metadata,
        String validity
) {
}
