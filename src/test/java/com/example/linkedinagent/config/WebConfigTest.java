package com.example.linkedinagent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WebConfigTest {

    @Test
    void trimsOriginsAndIgnoresEmptyEntries() {
        assertThat(WebConfig.parseAllowedOrigins("https://app.example.com, https://admin.example.com, "))
                .containsExactly("https://app.example.com", "https://admin.example.com");
    }

    @Test
    void emptyConfigurationDoesNotEnableCors() {
        assertThat(WebConfig.parseAllowedOrigins(" , ")).isEqualTo(List.of());
    }

    @Test
    void rejectsWildcardAndNonOriginUrls() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WebConfig.parseAllowedOrigins("*"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WebConfig.parseAllowedOrigins("https://app.example.com/private"));
    }
}
