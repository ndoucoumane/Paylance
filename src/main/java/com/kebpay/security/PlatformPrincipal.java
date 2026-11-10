package com.kebpay.security;

import com.kebpay.domain.enums.Platform;

/**
 * Principal Spring Security représentant la plateforme cliente authentifiée par API key.
 */
public record PlatformPrincipal(Platform platform) {
}
