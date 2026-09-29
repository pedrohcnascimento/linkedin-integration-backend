package com.example.linkedinagent.adapter.out.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Converter(autoApply = true)
public class InstantStringConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant value) {
        return value == null ? null : value.toString();
    }

    @Override
    public Instant convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            if (value.matches("-?\\d+")) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            throw exception;
        }
    }
}
