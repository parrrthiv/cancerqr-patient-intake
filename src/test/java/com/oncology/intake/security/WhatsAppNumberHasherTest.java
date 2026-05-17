package com.oncology.intake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppNumberHasherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private WhatsAppNumberHasher newHasher(MockEnvironment env, String key) {
        WhatsAppNumberHasher h = new WhatsAppNumberHasher(env, key);
        h.initialise();
        return h;
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("strips spaces, plus, hyphens, parens — keeps digits only")
        void stripsNonDigits() {
            assertEquals("919876543210", WhatsAppNumberHasher.normalise("+91 98765 43210"));
            assertEquals("919876543210", WhatsAppNumberHasher.normalise("+91-98765-43210"));
            assertEquals("919876543210", WhatsAppNumberHasher.normalise("(91) 98765.43210"));
            assertEquals("919876543210", WhatsAppNumberHasher.normalise("919876543210"));
        }

        @Test
        @DisplayName("handles null")
        void handlesNull() {
            assertNull(WhatsAppNumberHasher.normalise(null));
        }
    }

    @Nested
    @DisplayName("hashing")
    class Hashing {

        @Test
        @DisplayName("same human, multiple formats, same hash")
        void formatsCollideToSameHash() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = newHasher(env, KEY);

            String h1 = h.hash("+91 98765 43210");
            String h2 = h.hash("919876543210");
            String h3 = h.hash("+91-98765-43210");

            assertEquals(h1, h2);
            assertEquals(h2, h3);
        }

        @Test
        @DisplayName("different numbers produce different hashes")
        void differentNumbersDifferentHashes() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = newHasher(env, KEY);

            assertNotEquals(h.hash("919876543210"), h.hash("919876543211"));
        }

        @Test
        @DisplayName("with key configured: hash is base64-encoded 32 bytes")
        void hashIsBase64Sha256() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = newHasher(env, KEY);

            String hash = h.hash("919876543210");
            // HMAC-SHA256 → 32 bytes → 44 base64 chars (with padding).
            assertEquals(44, hash.length());
            // Base64 decode should yield 32 bytes.
            assertEquals(32, Base64.getDecoder().decode(hash).length);
        }

        @Test
        @DisplayName("dev fallback: no key configured → returns normalised number")
        void devFallback() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = newHasher(env, "");

            assertEquals("919876543210", h.hash("+91 98765 43210"));
        }

        @Test
        @DisplayName("null input → null output")
        void nullInput() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = newHasher(env, KEY);

            assertNull(h.hash(null));
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class ConfigValidation {

        @Test
        @DisplayName("production + no key = fail-fast")
        void productionRequiresKey() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("production");
            WhatsAppNumberHasher h = new WhatsAppNumberHasher(env, "");
            assertThrows(IllegalStateException.class, h::initialise);
        }

        @Test
        @DisplayName("invalid base64 = fail-fast")
        void invalidBase64() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = new WhatsAppNumberHasher(env, "!!!not-base64");
            assertThrows(IllegalStateException.class, h::initialise);
        }

        @Test
        @DisplayName("wrong-length key = fail-fast")
        void wrongLength() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            WhatsAppNumberHasher h = new WhatsAppNumberHasher(env,
                    Base64.getEncoder().encodeToString(new byte[16]));
            assertThrows(IllegalStateException.class, h::initialise);
        }
    }
}
