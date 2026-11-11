package com.example.linkedinagent.repository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstantStringConverterTest {

    private final InstantStringConverter converter = new InstantStringConverter();

    @Test
    void convertsInstantToIso8601AndBackIncludingLegacyEpochMilliseconds() {
        Instant instant = Instant.parse("2026-10-30T12:34:56.789Z");

        String column = converter.convertToDatabaseColumn(instant);
        assertThat(column).isEqualTo("2026-10-30T12:34:56.789Z");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(instant);

        String legacyEpochMillis = Long.toString(instant.toEpochMilli());
        assertThat(converter.convertToEntityAttribute(legacyEpochMillis)).isEqualTo(instant);

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsMalformedTimestampValue() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-a-timestamp"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
