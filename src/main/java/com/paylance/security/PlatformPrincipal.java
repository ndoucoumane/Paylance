package com.paylance.security;

import com.paylance.domain.enums.Platform;

/**
 * Principal Spring Security représentant la plateforme cliente authentifiée par API key.
 */
public record PlatformPrincipal(Platform platform) {
}
