package com.example.linkedinagent.adapter.out.security;

import com.example.linkedinagent.application.linkedin.TokenEncryptionException;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class AesGcmTokenCipher implements TokenCipherPort {

    private static final String FORMAT_VERSION = "v1";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH_BYTES = 32;

    private final AppProperties.Security properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmTokenCipher(AppProperties appProperties) {
        this.properties = appProperties.getSecurity();
    }

    @Override
    public void validateConfiguration() {
        key();
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new TokenEncryptionException("Token must not be blank.");
        }

        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return FORMAT_VERSION + "." + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new TokenEncryptionException("Unable to protect LinkedIn credentials.");
        }
    }

    @Override
    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || !encryptedValue.startsWith(FORMAT_VERSION + ".")) {
            throw new TokenEncryptionException("Stored LinkedIn credential has an unsupported format.");
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encryptedValue.substring(FORMAT_VERSION.length() + 1));
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
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new TokenEncryptionException("Unable to decrypt stored LinkedIn credentials.");
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private SecretKeySpec key() {
        String configuredKey = properties.getTokenEncryptionKey();
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new TokenEncryptionException("LinkedIn token encryption is not configured.");
        }

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
        SecretKeySpec key = new SecretKeySpec(decodedKey, "AES");
        Arrays.fill(decodedKey, (byte) 0);
        return key;
    }
}
