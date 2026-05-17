package com.oncology.intake.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts Integer entity fields via {@link PhiEncryptor}.
 *
 * Stored on disk as a string ({@code "7"} → {@code "{enc:v1}<base64>"}).
 * Apply per-field with {@code @Convert(converter = EncryptedIntegerConverter.class)}.
 */
@Component
@Converter(autoApply = false)
public class EncryptedIntegerConverter implements AttributeConverter<Integer, String> {

    private final PhiEncryptor encryptor;

    public EncryptedIntegerConverter(PhiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(Integer attribute) {
        if (attribute == null) return null;
        return encryptor.encrypt(attribute.toString());
    }

    @Override
    public Integer convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String plaintext = encryptor.decrypt(dbData);
        if (plaintext == null || plaintext.isEmpty()) return null;
        return Integer.valueOf(plaintext);
    }
}
