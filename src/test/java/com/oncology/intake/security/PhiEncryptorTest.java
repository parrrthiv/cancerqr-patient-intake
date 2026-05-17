package com.oncology.intake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PhiEncryptor}.
 *
 * <p>Focuses on the contract that every other PHI-encryption code path depends on:
 * round-trip identity, legacy plaintext pass-through, multi-version routing, and
 * fail-fast on misconfiguration.
 */
class PhiEncryptorTest {

    /** A valid base64 AES-256 key. Test value only — do not use anywhere real. */
    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    /** A different valid key for multi-key tests. */
    private static final String KEY_B;
    static {
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) b[i] = (byte) i;
        KEY_B = Base64.getEncoder().encodeToString(b);
    }

    private PhiEncryptor newEncryptor(MockEnvironment env) {
        PhiEncryptor e = new PhiEncryptor(env);
        e.initialise();
        return e;
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("encrypts then decrypts a string to itself")
        void roundTrip() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(env);

            String plaintext = "Rajesh Kumar";
            String ciphertext = e.encrypt(plaintext);

            assertNotNull(ciphertext);
            assertTrue(ciphertext.startsWith("{enc:v1}"), "Ciphertext should carry v1 prefix");
            assertNotEquals(plaintext, ciphertext);
            assertEquals(plaintext, e.decrypt(ciphertext));
        }

        @Test
        @DisplayName("two encryptions of the same plaintext produce different ciphertext (random IV)")
        void differentCiphertextOnRepeatedCalls() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(env);

            String c1 = e.encrypt("same value");
            String c2 = e.encrypt("same value");
            assertNotEquals(c1, c2, "Random IV per write should yield different ciphertexts");
            assertEquals(e.decrypt(c1), e.decrypt(c2));
        }

        @Test
        @DisplayName("null and empty inputs pass through unchanged")
        void passThroughEmpty() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(env);

            assertNull(e.encrypt(null));
            assertEquals("", e.encrypt(""));
            assertNull(e.decrypt(null));
            assertEquals("", e.decrypt(""));
        }
    }

    @Nested
    @DisplayName("legacy plaintext compatibility")
    class LegacyCompat {

        @Test
        @DisplayName("values without {enc:v<N>} prefix are returned unchanged")
        void legacyPlaintextPassThrough() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(env);

            // Simulates a row written before encryption was rolled out.
            assertEquals("legacy-name", e.decrypt("legacy-name"));
            assertEquals("72.50", e.decrypt("72.50"));
        }

        @Test
        @DisplayName("isEncrypted() distinguishes prefixed from legacy values")
        void isEncryptedRecognizesPrefix() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(env);

            assertFalse(e.isEncrypted("legacy-plaintext"));
            assertFalse(e.isEncrypted(null));
            assertTrue(e.isEncrypted(e.encrypt("anything")));
        }
    }

    @Nested
    @DisplayName("multi-version routing")
    class MultiVersion {

        @Test
        @DisplayName("writes use the highest configured version")
        void writesUseHighestVersion() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);    // v1
            env.setProperty("phi.encryption.key.v2", KEY_B); // v2
            PhiEncryptor e = newEncryptor(env);

            assertEquals(2, e.getCurrentVersion());
            String ciphertext = e.encrypt("hello");
            assertTrue(ciphertext.startsWith("{enc:v2}"));
        }

        @Test
        @DisplayName("reads v1 ciphertext with v1 key while writes use v2")
        void readsLegacyVersion() {
            // Step 1: only v1 configured — encrypt under v1.
            MockEnvironment envV1 = new MockEnvironment();
            envV1.setActiveProfiles("dev");
            envV1.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e1 = newEncryptor(envV1);
            String v1Ciphertext = e1.encrypt("legacy data");
            assertTrue(v1Ciphertext.startsWith("{enc:v1}"));

            // Step 2: now both v1 and v2 configured — should still decrypt v1.
            MockEnvironment envBoth = new MockEnvironment();
            envBoth.setActiveProfiles("dev");
            envBoth.setProperty("phi.encryption.key", KEY_A);
            envBoth.setProperty("phi.encryption.key.v2", KEY_B);
            PhiEncryptor e2 = newEncryptor(envBoth);

            assertEquals("legacy data", e2.decrypt(v1Ciphertext));
            // And new writes go under v2.
            assertTrue(e2.encrypt("new data").startsWith("{enc:v2}"));
        }

        @Test
        @DisplayName("isLegacyVersion identifies older ciphertext")
        void isLegacyVersionDetectsOldRows() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            env.setProperty("phi.encryption.key.v2", KEY_B);
            PhiEncryptor e = newEncryptor(env);

            // Hand-craft a v1 ciphertext using a temporary v1-only encryptor.
            MockEnvironment envV1 = new MockEnvironment();
            envV1.setActiveProfiles("dev");
            envV1.setProperty("phi.encryption.key", KEY_A);
            String v1Cipher = newEncryptor(envV1).encrypt("data");
            String v2Cipher = e.encrypt("data");

            assertTrue(e.isLegacyVersion(v1Cipher), "v1 row when v2 is current");
            assertFalse(e.isLegacyVersion(v2Cipher), "v2 row when v2 is current");
            assertFalse(e.isLegacyVersion("legacy plaintext"));
        }

        @Test
        @DisplayName("decrypting v2 ciphertext with no v2 key throws")
        void missingKeyForCiphertextVersion() {
            // Encrypt under v2.
            MockEnvironment envV2 = new MockEnvironment();
            envV2.setActiveProfiles("dev");
            envV2.setProperty("phi.encryption.key", KEY_A);
            envV2.setProperty("phi.encryption.key.v2", KEY_B);
            String v2Cipher = newEncryptor(envV2).encrypt("data");

            // Now configure only v1 (operator removed v2 prematurely).
            MockEnvironment envV1Only = new MockEnvironment();
            envV1Only.setActiveProfiles("dev");
            envV1Only.setProperty("phi.encryption.key", KEY_A);
            PhiEncryptor e = newEncryptor(envV1Only);

            assertThrows(IllegalStateException.class, () -> e.decrypt(v2Cipher));
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class ConfigValidation {

        @Test
        @DisplayName("production profile + no key configured = fail-fast")
        void productionRequiresKey() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("production");
            PhiEncryptor e = new PhiEncryptor(env);
            assertThrows(IllegalStateException.class, e::initialise);
        }

        @Test
        @DisplayName("non-production profile + no key = warns and pass-through")
        void devFallbackPassesThrough() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            PhiEncryptor e = newEncryptor(env);

            assertEquals("plain", e.encrypt("plain"),
                    "No key configured -> pass-through, no {enc:} prefix");
            assertEquals("plain", e.decrypt("plain"));
        }

        @Test
        @DisplayName("invalid base64 key = fail-fast")
        void invalidBase64() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", "!!!not-base64!!!");
            PhiEncryptor e = new PhiEncryptor(env);
            assertThrows(IllegalStateException.class, e::initialise);
        }

        @Test
        @DisplayName("key not 32 bytes = fail-fast")
        void wrongKeyLength() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key",
                    Base64.getEncoder().encodeToString(new byte[16])); // 128-bit
            PhiEncryptor e = new PhiEncryptor(env);
            assertThrows(IllegalStateException.class, e::initialise);
        }

        @Test
        @DisplayName("conflicting v1 keys via two env vars = fail-fast")
        void conflictingV1Keys() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            env.setProperty("phi.encryption.key.v1", KEY_B);
            PhiEncryptor e = new PhiEncryptor(env);
            assertThrows(IllegalStateException.class, e::initialise);
        }

        @Test
        @DisplayName("PHI_ENCRYPTION_KEY and PHI_ENCRYPTION_KEY_V1 set to the same value is fine")
        void sameKeyConfiguredTwice() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("dev");
            env.setProperty("phi.encryption.key", KEY_A);
            env.setProperty("phi.encryption.key.v1", KEY_A);
            PhiEncryptor e = newEncryptor(env);
            assertEquals(1, e.getCurrentVersion());
            assertEquals("ok", e.decrypt(e.encrypt("ok")));
        }
    }
}
