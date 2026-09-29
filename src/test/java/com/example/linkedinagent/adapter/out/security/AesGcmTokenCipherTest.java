package com.example.linkedinagent.adapter.out.security;

import com.example.linkedinagent.application.linkedin.TokenEncryptionException;
import com.example.linkedinagent.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenCipherTest {

    private static final byte[] KEY = new byte[32];

    @Test
    void encryptsTokensWithVersionedAuthenticatedCiphertextAndUniqueNonces() throws Exception {
        AesGcmTokenCipher cipher = cipher(Base64.getEncoder().encodeToString(KEY));

        String first = cipher.encrypt("access-token");
        String second = cipher.encrypt("access-token");

        assertThat(first).startsWith("v1.");
        assertThat(second).startsWith("v1.");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("access-token");
    }

    @Test
    void rejectsTamperedCiphertextAndInvalidEncryptionKeys() {
        AesGcmTokenCipher cipher = cipher(Base64.getEncoder().encodeToString(KEY));
        String encrypted = cipher.encrypt("access-token");
        byte[] payload = Base64.getDecoder().decode(encrypted.substring("v1.".length()));
        payload[payload.length - 1] ^= 1;
        String tampered = "v1." + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(TokenEncryptionException.class);
        assertThatThrownBy(() -> cipher("not-base64").encrypt("access-token"))
                .isInstanceOf(TokenEncryptionException.class);
        assertThatThrownBy(() -> cipher("not-base64").validateConfiguration())
                .isInstanceOf(TokenEncryptionException.class);
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]))
                .encrypt("access-token"))
                .isInstanceOf(TokenEncryptionException.class);
    }

    private static AesGcmTokenCipher cipher(String configuredKey) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenEncryptionKey(configuredKey);
        return new AesGcmTokenCipher(properties);
    }

}
