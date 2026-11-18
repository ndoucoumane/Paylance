package com.paylance.security.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Chiffrement applicatif AES-256-GCM des secrets clients (identifiants prestataires, secrets
 * webhook, secrets de signature des callbacks).
 *
 * <p>Format stocké (JSON) : {@code {"v":1,"kid":"<empreinte de la clé>","iv":"<b64>","ct":"<b64>"}}.
 * {@code ct} inclut le tag d'authentification GCM. La donnée associée (AAD) lie le texte chiffré
 * à son propriétaire (organisation + usage) : un chiffré recopié vers un autre tenant ou un autre
 * champ ne se déchiffre pas.
 *
 * <p>La clé maîtresse vient de {@code PAYLANCE_ENCRYPTION_MASTER_KEY} (32 octets en base64).
 * TODO(rotation) : {@code kid} permet de détecter une clé différente ; la rotation (déchiffrer avec
 * l'ancienne clé, rechiffrer avec la nouvelle) n'est pas encore outillée.
 */
@Component
public class EncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int FORMAT_VERSION = 1;

    private final SecretKey key;
    private final String keyId;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${paylance.security.encryption-master-key:}") String base64MasterKey,
                             ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            throw new IllegalStateException("PAYLANCE_ENCRYPTION_MASTER_KEY est requis (clé AES-256 en base64)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64MasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAYLANCE_ENCRYPTION_MASTER_KEY n'est pas du base64 valide");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("PAYLANCE_ENCRYPTION_MASTER_KEY doit faire 32 octets (AES-256), reçu : " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = fingerprint(raw);
    }

    public String encrypt(String plaintext, String associatedData) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("v", FORMAT_VERSION);
            envelope.put("kid", keyId);
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("ct", Base64.getEncoder().encodeToString(ct));
            return objectMapper.writeValueAsString(envelope);
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Échec du chiffrement", e);
        }
    }

    public String decrypt(String envelopeJson, String associatedData) {
        if (envelopeJson == null) {
            return null;
        }
        try {
            var envelope = objectMapper.readTree(envelopeJson);
            if (envelope.path("v").asInt() != FORMAT_VERSION) {
                throw new IllegalStateException("Version de chiffrement non supportée");
            }
            if (!keyId.equals(envelope.path("kid").asText())) {
                throw new IllegalStateException("Donnée chiffrée avec une autre clé maîtresse (kid=" + envelope.path("kid").asText() + ")");
            }
            byte[] iv = Base64.getDecoder().decode(envelope.path("iv").asText());
            byte[] ct = Base64.getDecoder().decode(envelope.path("ct").asText());
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Échec du déchiffrement (clé ou contexte invalide)", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Format chiffré invalide", e);
        }
    }

    private static String fingerprint(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
