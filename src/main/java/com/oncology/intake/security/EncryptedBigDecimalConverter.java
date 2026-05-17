package com.oncology.intake.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Encrypts BigDecimal entity fields via {@link PhiEncryptor}.
 *
 * Stored on disk as a string ({@code "72.50"} → {@code "{enc:v1}<base64>"}).
 * Loss of decimal precision is impossible because we use {@link BigDecimal#toPlainString()}
 * (no scientific notation) and parse with the string constructor on read.
 *
 * Apply per-field with {@code @Convert(converter = EncryptedBigDecimalConverter.class)}.
 * Never set {@code autoApply = true} — every BigDecimal column in the model would
 * become encrypted, including non-PHI ones.
 */
@Component
@Converter(autoApply = false)
public class EncryptedBigDecimalConverter implements AttributeConverter<BigDecimal, String> {

    private final PhiEncryptor encryptor;

    public EncryptedBigDecimalConverter(PhiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(BigDecimal attribute) {
        if (attribute == null) return null;
        return encryptor.encrypt(attribute.toPlainString());
    }

    @Override
    public BigDecimal convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        String plaintext = encryptor.decrypt(dbData);
        if (plaintext == null || plaintext.isEmpty()) return null;
        return new BigDecimal(plaintext);
    }
}
