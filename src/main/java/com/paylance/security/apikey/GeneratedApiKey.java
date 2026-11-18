package com.paylance.security.apikey;

import com.paylance.domain.entity.ApiKey;

/** Clé fraîchement créée : {@code plaintext} n'est disponible qu'à cet instant, jamais stocké. */
public record GeneratedApiKey(ApiKey apiKey, String plaintext) {
}
