package com.paylance.security.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private final EncryptionService service = new EncryptionService(randomKey(), new ObjectMapper());

    @Test
    void roundTrip_restoresPlaintext_andStoredJsonDoesNotContainIt() {
        String secret = "{\"checkoutApiKey\":\"wave_sn_prod_abcdef123456\"}";

        String stored = service.encrypt(secret, "org-1:provider_account.credentials");

        assertThat(stored).doesNotContain("wave_sn_prod_abcdef123456").contains("\"kid\"").contains("\"iv\"");
        assertThat(service.decrypt(stored, "org-1:provider_account.credentials")).isEqualTo(secret);
    }

    @Test
    void sameValueEncryptedTwice_producesDifferentCiphertexts() {
        assertThat(service.encrypt("x", "aad")).isNotEqualTo(service.encrypt("x", "aad"));
    }

    @Test
    void decrypt_withOtherAssociatedData_fails() {
        String stored = service.encrypt("secret", "org-A:provider_account.credentials");

        assertThatThrownBy(() -> service.decrypt(stored, "org-B:provider_account.credentials"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_withOtherMasterKey_fails() {
        String stored = service.encrypt("secret", "aad");
        EncryptionService other = new EncryptionService(randomKey(), new ObjectMapper());

        assertThatThrownBy(() -> other.decrypt(stored, "aad")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsMissingOrInvalidKey() {
        assertThatThrownBy(() -> new EncryptionService("", new ObjectMapper())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncryptionService(Base64.getEncoder().encodeToString(new byte[16]), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }
}
