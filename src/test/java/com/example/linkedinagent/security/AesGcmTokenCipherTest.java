package com.example.linkedinagent.security;

import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.TokenEncryptionException;
import org.junit.jupiter.api.Test;

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

    @Test
    void missingKeyIsReportedWhenEncryptionIsValidatedNotWhenCipherIsConstructed() {
        AppProperties properties = new AppProperties();
        assertThatCode(() -> new AesGcmTokenCipher(properties)).doesNotThrowAnyException();

        AesGcmTokenCipher cipher = new AesGcmTokenCipher(properties);
        assertThatThrownBy(cipher::validateConfiguration)
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption is not configured.");
    }

    @Test
    void encryptsWithDistinctRandomNoncesAndDecryptsBackToOriginalPlaintext() {
        AesGcmTokenCipher cipher = cipherWithKey(VALID_KEY);

        String first = cipher.encrypt("linkedin-access-token");
        String second = cipher.encrypt("linkedin-access-token");

        assertThat(first).startsWith("v1.").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("linkedin-access-token");
        assertThat(cipher.decrypt(second)).isEqualTo("linkedin-access-token");
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
