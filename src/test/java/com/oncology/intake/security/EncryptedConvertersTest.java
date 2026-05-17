package com.oncology.intake.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the three JPA {@code AttributeConverter}s correctly delegate to
 * {@link PhiEncryptor} and handle type-specific edge cases.
 */
class EncryptedConvertersTest {

    private PhiEncryptor encryptor;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("phi.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
        encryptor = new PhiEncryptor(env);
        encryptor.initialise();
    }

    @Nested
    @DisplayName("EncryptedStringConverter")
    class StringConverter {

        @Test
        @DisplayName("round-trip identity")
        void roundTrip() {
            EncryptedStringConverter c = new EncryptedStringConverter(encryptor);
            String dbValue = c.convertToDatabaseColumn("Rajesh Kumar");
            assertTrue(dbValue.startsWith("{enc:v1}"));
            assertEquals("Rajesh Kumar", c.convertToEntityAttribute(dbValue));
        }

        @Test
        @DisplayName("null passes through")
        void nullPassThrough() {
            EncryptedStringConverter c = new EncryptedStringConverter(encryptor);
            assertNull(c.convertToDatabaseColumn(null));
            assertNull(c.convertToEntityAttribute(null));
        }
    }

    @Nested
    @DisplayName("EncryptedBigDecimalConverter")
    class BigDecimalConverter {

        @Test
        @DisplayName("preserves precision via toPlainString")
        void preservesPrecision() {
            EncryptedBigDecimalConverter c = new EncryptedBigDecimalConverter(encryptor);
            BigDecimal original = new BigDecimal("72.50");
            String dbValue = c.convertToDatabaseColumn(original);
            assertTrue(dbValue.startsWith("{enc:v1}"));
            BigDecimal roundTripped = c.convertToEntityAttribute(dbValue);
            // BigDecimal equality is scale-sensitive — toPlainString preserves it.
            assertEquals(original, roundTripped);
            assertEquals(2, roundTripped.scale());
        }

        @Test
        @DisplayName("handles whole numbers, negatives, zero")
        void variousValues() {
            EncryptedBigDecimalConverter c = new EncryptedBigDecimalConverter(encryptor);
            for (BigDecimal v : new BigDecimal[]{
                    BigDecimal.ZERO,
                    new BigDecimal("100"),
                    new BigDecimal("-15.75"),
                    new BigDecimal("0.001")
            }) {
                assertEquals(v, c.convertToEntityAttribute(c.convertToDatabaseColumn(v)));
            }
        }

        @Test
        @DisplayName("null passes through")
        void nullPassThrough() {
            EncryptedBigDecimalConverter c = new EncryptedBigDecimalConverter(encryptor);
            assertNull(c.convertToDatabaseColumn(null));
            assertNull(c.convertToEntityAttribute(null));
        }

        @Test
        @DisplayName("legacy plaintext rows decode correctly")
        void legacyPlaintext() {
            EncryptedBigDecimalConverter c = new EncryptedBigDecimalConverter(encryptor);
            // Pre-encryption row: BigDecimal stored as "72.50" string after V8 migration.
            assertEquals(new BigDecimal("72.50"), c.convertToEntityAttribute("72.50"));
        }
    }

    @Nested
    @DisplayName("EncryptedIntegerConverter")
    class IntegerConverter {

        @Test
        @DisplayName("round-trip identity")
        void roundTrip() {
            EncryptedIntegerConverter c = new EncryptedIntegerConverter(encryptor);
            for (int v : new int[]{0, 1, 7, 10, -5, 100, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                String dbValue = c.convertToDatabaseColumn(v);
                assertTrue(dbValue.startsWith("{enc:v1}"));
                assertEquals(Integer.valueOf(v), c.convertToEntityAttribute(dbValue));
            }
        }

        @Test
        @DisplayName("null passes through")
        void nullPassThrough() {
            EncryptedIntegerConverter c = new EncryptedIntegerConverter(encryptor);
            assertNull(c.convertToDatabaseColumn(null));
            assertNull(c.convertToEntityAttribute(null));
        }

        @Test
        @DisplayName("legacy plaintext rows decode correctly")
        void legacyPlaintext() {
            EncryptedIntegerConverter c = new EncryptedIntegerConverter(encryptor);
            assertEquals(Integer.valueOf(6), c.convertToEntityAttribute("6"));
        }
    }
}
