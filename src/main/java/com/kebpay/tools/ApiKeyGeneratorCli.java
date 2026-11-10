package com.kebpay.tools;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Outil en ligne de commande pour générer une clé API en clair (à transmettre une seule
 * fois à la plateforme cliente) et son hash BCrypt (à insérer dans {@code platform_api_keys}).
 * Usage : {@code mvn -q compile exec:java -Dexec.mainClass=com.kebpay.tools.ApiKeyGeneratorCli}
 */
public final class ApiKeyGeneratorCli {

    private ApiKeyGeneratorCli() {
    }

    public static void main(String[] args) {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String rawKey = "kbp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String hash = new BCryptPasswordEncoder(12).encode(rawKey);

        System.out.println("Clé API en clair (à transmettre une seule fois à la plateforme) :");
        System.out.println(rawKey);
        System.out.println();
        System.out.println("Hash BCrypt (à stocker dans platform_api_keys.api_key_hash) :");
        System.out.println(hash);
    }
}
