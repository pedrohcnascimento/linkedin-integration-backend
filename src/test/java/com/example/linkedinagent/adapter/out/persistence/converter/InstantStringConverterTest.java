package com.example.linkedinagent.adapter.out.persistence.converter;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InstantStringConverterTest {

    private final InstantStringConverter converter = new InstantStringConverter();

    @Test
    void storesInstantsAsIso8601Text() {
        Instant instant = Instant.parse("2026-09-28T21:00:00.123456Z");

        assertThat(converter.convertToDatabaseColumn(instant)).isEqualTo("2026-09-28T21:00:00.123456Z");
        assertThat(converter.convertToEntityAttribute("2026-09-28T21:00:00.123456Z")).isEqualTo(instant);
    }

    @Test
    void readsLegacyEpochMillisecondValues() {
        assertThat(converter.convertToEntityAttribute("1790643662935"))
                .isEqualTo(Instant.ofEpochMilli(1790643662935L));
    }
}
