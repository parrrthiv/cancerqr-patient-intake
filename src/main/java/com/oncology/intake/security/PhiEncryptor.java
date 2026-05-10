package com.oncology.intake.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts / decrypts PHI strings using AES-256-GCM.
 *
 * <h2>Threat model</h2>
 * Protects against leaked RDS snapshots, accidental backup misplacement, and
 * read-only DB credential compromise. Does NOT protect against full app-server
 * compromise — once an attacker has the running JVM, they have the key.
 *
 * <h2>Output format</h2>
 * <pre>
 *   {enc:v1}&lt;base64(IV || ciphertext || GCM tag)&gt;
 * </pre>
 * The prefix marks ciphertext (so legacy plaintext rows are recognisable and
 * can pass through {@link #decrypt(String)} unchanged) and versions the
 * algorithm + key so rotation is possible later: a future {@code v2} can use a
 * new key while still decrypting {@code v1} rows with the old one.
 *
 * <h2>Key</h2>
 * A 32-byte AES-256 key, base64-encoded into the {@code PHI_ENCRYPTION_KEY}
 * env var. Generate with:
 * <pre>
 *   openssl rand -base64 32
 * </pre>
 * <strong>Loss of this key is permanent loss of every encrypted PHI value
 * in the database.</strong> Back it up alongside (but not in!) your other
 * production secrets. Rotation requires re-encrypting every row that uses v1 —
 * see TODO at end of file.
 *
 * <h2>Failure modes</h2>
 * <ul>
 *   <li>Production profile + missing key: fail-fast at startup. Better to
 *       refuse to start than to silently store PHI in plaintext.</li>
 *   <li>Other profiles + missing key: log a loud warning, write/read in
 *       plaintext (developer convenience for H2 local dev).</li>
 *   <li>Decrypt failure: throws {@link IllegalStateException}. Hibernate
 *       turns this into a fetch error — preferable to silently returning
 *       garbage.</li>
 * </ul>
 *
 * @see EncryptedStringConverter
 */
@Service
@Slf4j
public class PhiEncryptor {

    /** Marker on every ciphertext value. v1 = AES-256-GCM with 12-byte IV. */
    private static final String V1_PREFIX = "{enc:v1}";

    /** GCM authentication tag length in bits — the standard 128. */
    private static final int GCM_TAG_LENGTH_BITS = 128;
    /** GCM IV length in bytes — the recommended 12 for AES-GCM. */
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final Environment environment;
    private final String configuredKeyB64;
    private final SecureRandom random = new SecureRandom();

    /** Resolved AES key. Null when no key is configured (dev fallback). */
    private SecretKey key;

    public PhiEncryptor(Environment environment,
                        @Value("${phi.encryption.key:}") String configuredKeyB64) {
        this.environment = environment;
        this.configuredKeyB64 = configuredKeyB64;
    }

    @PostConstruct
    void initialise() {
        if (configuredKeyB64 == null || configuredKeyB64.isBlank()) {
            if (isProductionProfile()) {
                throw new IllegalStateException(
                        "PHI_ENCRYPTION_KEY must be set in the production profile. " +
                        "Generate with: openssl rand -base64 32");
            }
            log.warn("PHI_ENCRYPTION_KEY not set — PHI columns will be stored in PLAINTEXT. " +
                    "Acceptable for local H2 dev only.");
            return;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKeyB64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "PHI_ENCRYPTION_KEY is not valid base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "PHI_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256). " +
                    "Got " + decoded.length + " bytes. Generate with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        log.info("PHI encryptor initialised (AES-256-GCM, key id={})", keyFingerprint(decoded));
    }

    /**
     * Encrypt a value for storage. Null and empty strings pass through
     * unchanged (no point ciphertext-tagging an empty value, and JPA preserves
     * NULL semantics for indexes / IS NULL queries).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (key == null) {
            // No key configured — pass-through (dev only; warning logged at startup).
            return plaintext;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return V1_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Don't include `plaintext` in the message — we'd be logging the very PHI we're encrypting.
            throw new IllegalStateException("PHI encryption failed", e);
        }
    }

    /**
     * Decrypt a value read from storage. Strings without the {@code {enc:v1}}
     * prefix are treated as legacy plaintext and returned as-is — this is what
     * lets us roll out encryption without backfilling the whole table.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(V1_PREFIX)) {
            // Legacy plaintext or already-decrypted value. Pass through.
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "Encrypted value found but PHI_ENCRYPTION_KEY is not configured. " +
                    "Set the key or remove the column data.");
        }

        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(V1_PREFIX.length()));
            if (combined.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalStateException("Ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PHI decryption failed (key mismatch or corruption?)", e);
        }
    }

    private boolean isProductionProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    /**
     * First 4 bytes of SHA-256(key), as hex. Lets you confirm two environments
     * are using the same key without ever logging the key itself.
     */
    private static String keyFingerprint(byte[] keyBytes) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(keyBytes);
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // TODO (key rotation, future PR):
    //   1. Introduce {enc:v2} writing under a new PHI_ENCRYPTION_KEY_V2.
    //   2. Keep PHI_ENCRYPTION_KEY (v1) configured to keep decrypting old rows.
    //   3. Add an admin endpoint or CLI that re-saves every row through Hibernate
    //      so each encrypted column is rewritten under v2 on flush.
    //   4. Once a sweep confirms zero {enc:v1} rows remain, retire the v1 key.
}
