package com.example.linkedinagent.security;

import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.TokenEncryptionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenCipherTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    private static final String ALL_ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void missingOrWeakKeyFailsFastAtStartupOutsideLocalAndRejectsWeakKeysOnValidation() {
        AppProperties properties = new AppProperties();
        MockEnvironment localEnvironment = new MockEnvironment().withProperty("spring.profiles.active", "local");
        localEnvironment.setActiveProfiles("local");
        assertThatCode(() -> new AesGcmTokenCipher(properties, localEnvironment)).doesNotThrowAnyException();

        MockEnvironment prodEnvironment = new MockEnvironment();
        prodEnvironment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new AesGcmTokenCipher(properties, prodEnvironment))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption is not configured.");

        properties.getSecurity().setTokenEncryptionKey(ALL_ZERO_KEY);
        assertThatThrownBy(() -> new AesGcmTokenCipher(properties, prodEnvironment))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption key is too weak; generate 32 random bytes.");
    }

    @Test
    void encryptsWithDistinctRandomNoncesKeyIdAndAadAndSupportsKeyRotation() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenEncryptionKey("k1:" + VALID_KEY);
        AesGcmTokenCipher cipher = new AesGcmTokenCipher(properties);

        String first = cipher.encrypt("linkedin-access-token", "user-123");
        String second = cipher.encrypt("linkedin-access-token", "user-123");

        assertThat(first).startsWith("v1.k1.").isNotEqualTo(second);
        assertThat(cipher.decrypt(first, "user-123")).isEqualTo("linkedin-access-token");
        assertThat(cipher.decrypt(second, "user-123")).isEqualTo("linkedin-access-token");
        assertThatThrownBy(() -> cipher.decrypt(first, "different-user-456"))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Unable to decrypt stored LinkedIn credentials.");

        // Rotate to k2 while keeping k1 in the key ring for decrypting existing rows
        properties.getSecurity().setTokenEncryptionKey("k2:" + OTHER_KEY + ", k1:" + VALID_KEY);
        AesGcmTokenCipher rotatedCipher = new AesGcmTokenCipher(properties);
        String encryptedWithK2 = rotatedCipher.encrypt("new-token", "user-123");
        assertThat(encryptedWithK2).startsWith("v1.k2.");
        assertThat(rotatedCipher.decrypt(first, "user-123")).isEqualTo("linkedin-access-token");
        assertThat(rotatedCipher.decrypt(encryptedWithK2, "user-123")).isEqualTo("new-token");
    }

    @Test
    void rejectsTamperedMalformedOrWrongKeyPayloadsAndInvalidKeyFormats() {
        AesGcmTokenCipher cipher = cipherWithKey(VALID_KEY);
        String encrypted = cipher.encrypt("sensitive-token");

        assertThatThrownBy(() -> cipher.encrypt("   "))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Token must not be blank.");

        assertThatThrownBy(() -> cipher.decrypt("v2.abc"))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Stored LinkedIn credential has an unsupported format.");

        assertThatThrownBy(() -> cipher.decrypt("v1.***not-base64***"))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Stored LinkedIn credential is malformed.");

        String shortPayload = "v1." + Base64.getEncoder().encodeToString(new byte[12]);
        assertThatThrownBy(() -> cipher.decrypt(shortPayload))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Stored LinkedIn credential is malformed.");

        AesGcmTokenCipher wrongKeyCipher = cipherWithKey(OTHER_KEY);
        assertThatThrownBy(() -> wrongKeyCipher.decrypt(encrypted))
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("Unable to decrypt stored LinkedIn credentials.");

        assertThatThrownBy(() -> cipherWithKey("not_base64!").validateConfiguration())
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption key must be valid Base64.");

        String sixteenByteKey = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> cipherWithKey(sixteenByteKey).validateConfiguration())
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption key must decode to exactly 32 bytes.");
    }

    private AesGcmTokenCipher cipherWithKey(String key) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setTokenEncryptionKey(key);
        return new AesGcmTokenCipher(properties);
    }
}
