package com.example.linkedinagent.security;

import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.TokenEncryptionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class AesGcmTokenCipher implements TokenCipher {

    private static final String FORMAT_VERSION = "v1";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH_BYTES = 32;
    private static final int MIN_DISTINCT_BYTES = 8;

    private final AppProperties.Security properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmTokenCipher(AppProperties appProperties) {
        this.properties = appProperties.getSecurity();
    }

    @Autowired
    public AesGcmTokenCipher(AppProperties appProperties, Environment environment) {
        this(appProperties);
        if (environment != null && !environment.acceptsProfiles(Profiles.of("local"))) {
            validateConfiguration();
        }
    }

    @Override
    public void validateConfiguration() {
        keyRing();
    }

    @Override
    public String encrypt(String plaintext) {
        return encrypt(plaintext, null);
    }

    @Override
    public String encrypt(String plaintext, String associatedData) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new TokenEncryptionException("Token must not be blank.");
        }

        KeyRing ring = keyRing();
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ring.activeKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return FORMAT_VERSION + "." + ring.activeKeyId() + "." + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new TokenEncryptionException("Unable to protect LinkedIn credentials.");
        }
    }

    @Override
    public String decrypt(String encryptedValue) {
        return decrypt(encryptedValue, null);
    }

    @Override
    public String decrypt(String encryptedValue, String associatedData) {
        if (encryptedValue == null || !encryptedValue.startsWith(FORMAT_VERSION + ".")) {
            throw new TokenEncryptionException("Stored LinkedIn credential has an unsupported format.");
        }

        KeyRing ring = keyRing();
        String remainder = encryptedValue.substring(FORMAT_VERSION.length() + 1);
        SecretKeySpec decryptionKey;
        String encodedPayload;
        int dotIndex = remainder.indexOf('.');
        if (dotIndex > 0) {
            String keyId = remainder.substring(0, dotIndex);
            encodedPayload = remainder.substring(dotIndex + 1);
            decryptionKey = ring.keys().get(keyId);
            if (decryptionKey == null) {
                throw new TokenEncryptionException("Unable to decrypt stored LinkedIn credentials.");
            }
        } else {
            encodedPayload = remainder;
            decryptionKey = ring.activeKey();
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encodedPayload);
        } catch (IllegalArgumentException exception) {
            throw new TokenEncryptionException("Stored LinkedIn credential is malformed.");
        }
        if (payload.length <= NONCE_LENGTH_BYTES) {
            throw new TokenEncryptionException("Stored LinkedIn credential is malformed.");
        }

        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_LENGTH_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new TokenEncryptionException("Unable to decrypt stored LinkedIn credentials.");
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private KeyRing keyRing() {
        String configuredKey = properties.getTokenEncryptionKey();
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new TokenEncryptionException("LinkedIn token encryption is not configured.");
        }
        String defaultKeyId = properties.getTokenEncryptionKeyId();
        if (defaultKeyId == null || defaultKeyId.isBlank() || defaultKeyId.contains(".")) {
            defaultKeyId = "k1";
        }

        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        String activeKeyId = null;
        for (String entry : configuredKey.split(",", -1)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String keyId;
            String base64Material;
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex > 0) {
                keyId = trimmed.substring(0, colonIndex).trim();
                base64Material = trimmed.substring(colonIndex + 1).trim();
            } else {
                keyId = defaultKeyId;
                base64Material = trimmed;
            }
            if (keyId.isEmpty() || keyId.contains(".")) {
                throw new TokenEncryptionException("LinkedIn token encryption key ID is invalid.");
            }
            keys.put(keyId, decodeSecretKey(base64Material));
            if (activeKeyId == null) {
                activeKeyId = keyId;
            }
        }
        if (activeKeyId == null) {
            throw new TokenEncryptionException("LinkedIn token encryption is not configured.");
        }
        return new KeyRing(activeKeyId, keys.get(activeKeyId), Map.copyOf(keys));
    }

    private SecretKeySpec decodeSecretKey(String configuredKey) {
        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException exception) {
            throw new TokenEncryptionException("LinkedIn token encryption key must be valid Base64.");
        }
        if (decodedKey.length != AES_KEY_LENGTH_BYTES) {
            Arrays.fill(decodedKey, (byte) 0);
            throw new TokenEncryptionException("LinkedIn token encryption key must decode to exactly 32 bytes.");
        }
        if (isWeakKey(decodedKey)) {
            Arrays.fill(decodedKey, (byte) 0);
            throw new TokenEncryptionException("LinkedIn token encryption key is too weak; generate 32 random bytes.");
        }
        SecretKeySpec key = new SecretKeySpec(decodedKey, "AES");
        Arrays.fill(decodedKey, (byte) 0);
        return key;
    }

    private record KeyRing(String activeKeyId, SecretKeySpec activeKey, Map<String, SecretKeySpec> keys) {
    }

    public static boolean isWeakKey(byte[] keyBytes) {
        Set<Byte> distinctBytes = new HashSet<>();
        for (byte value : keyBytes) {
            distinctBytes.add(value);
        }
        return distinctBytes.size() < MIN_DISTINCT_BYTES;
    }
}
