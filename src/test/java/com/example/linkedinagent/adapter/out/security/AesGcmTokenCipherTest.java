package com.example.linkedinagent.adapter.out.security;

import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.TokenEncryptionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmTokenCipherTest {

    @Test
    void missingKeyIsReportedWhenEncryptionIsValidatedNotWhenCipherIsConstructed() {
        AppProperties properties = new AppProperties();
        assertThatCode(() -> new AesGcmTokenCipher(properties)).doesNotThrowAnyException();

        AesGcmTokenCipher cipher = new AesGcmTokenCipher(properties);
        assertThatThrownBy(cipher::validateConfiguration)
                .isInstanceOf(TokenEncryptionException.class)
                .hasMessage("LinkedIn token encryption is not configured.");
    }
}
