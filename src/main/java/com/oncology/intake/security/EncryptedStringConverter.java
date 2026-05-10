package com.oncology.intake.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA {@link AttributeConverter} that pipes a {@code String} entity field
 * through {@link PhiEncryptor} on every write/read.
 *
 * <p>Apply per-field with {@code @Convert(converter = EncryptedStringConverter.class)}.
 * Do <strong>NOT</strong> set {@code autoApply = true} — every {@code String}
 * column would suddenly be encrypted, including UUIDs in string form, role
 * names, status enums, etc.
 *
 * <p>Spring Boot 3 + Hibernate 6 use {@code SpringBeanContainer} to
 * instantiate JPA converters, which is what makes constructor injection work
 * here. (In a plain JPA setup the converter would have to be no-arg and look
 * up the encryptor through a static bridge.)
 */
@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PhiEncryptor encryptor;

    public EncryptedStringConverter(PhiEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}
