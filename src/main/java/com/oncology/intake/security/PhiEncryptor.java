package com.oncology.intake.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encrypts / decrypts PHI strings using AES-256-GCM, with support for multiple
 * concurrent key versions to enable zero-downtime key rotation.
 *
 * <h2>Output format</h2>
 * <pre>
 *   {enc:v&lt;N&gt;}&lt;base64(IV || ciphertext || GCM tag)&gt;
 * </pre>
 *
 * <ul>
 *   <li>Writes always use the <em>current</em> version key (highest configured).</li>
 *   <li>Reads parse the {@code v<N>} prefix and pick the matching key from the map.</li>
 *   <li>Values without a prefix are treated as legacy plaintext and pass through
 *       unchanged — this is what enables the original rollout (PR 8) without a
 *       data backfill.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Keys are env vars (base64 32 bytes each, generated independently):
 * <ul>
 *   <li>{@code PHI_ENCRYPTION_KEY} → registered as v1 (back-compat name).</li>
 *   <li>{@code PHI_ENCRYPTION_KEY_V2}, {@code PHI_ENCRYPTION_KEY_V3}, ... → v2, v3, ...</li>
 *   <li>{@code PHI_ENCRYPTION_KEY_V1} also accepted (identical to PHI_ENCRYPTION_KEY).</li>
 * </ul>
 *
 * <h2>Rotation flow</h2>
 * <pre>
 *   1. Generate a new key. Add as PHI_ENCRYPTION_KEY_V2 to docker run.
 *      Both PHI_ENCRYPTION_KEY (v1) and the new V2 are now configured.
 *      Redeploy. Writes now use v2; reads handle either v1 or v2.
 *   2. POST /api/admin/phi/rotate (admin-only). Service re-saves every
 *      encrypted entity through Hibernate, drifting all rows to {enc:v2}.
 *   3. Once a sweep confirms zero {enc:v1} rows remain, remove
 *      PHI_ENCRYPTION_KEY (the v1 value) from docker run. Redeploy.
 *      The v1 key is now retired.
 * </pre>
 * The full procedure is documented in the operations runbook.
 *
 * <h2>Failure modes</h2>
 * <ul>
 *   <li>Production + no keys configured: fail-fast at startup.</li>
 *   <li>Other profiles + no keys: warn and pass-through (dev convenience).</li>
 *   <li>Read of {enc:v&lt;N&gt;} with no v&lt;N&gt; key configured:
 *       {@code IllegalStateException}. Operator must restore the missing key.</li>
 *   <li>GCM auth tag mismatch: {@code IllegalStateException}. Either wrong
 *       key or tampered ciphertext.</li>
 * </ul>
 */
@Service
@Slf4j
public class PhiEncryptor {

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^\\{enc:v(\\d+)}");
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    /** Upper bound on auto-discovered key versions. Higher than the practical max. */
    private static final int MAX_VERSION_PROBE = 20;

    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    /** Resolved AES keys by version. Insertion order preserved for logging. */
    private final Map<Integer, SecretKey> keysByVersion = new LinkedHashMap<>();

    /** Version used for new writes. Always the highest configured version. */
    @Getter
    private int currentVersion;

    public PhiEncryptor(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void initialise() {
        // PHI_ENCRYPTION_KEY (back-compat) — registers as v1 unless V1 is also set.
        addKeyIfConfigured(1, environment.getProperty("phi.encryption.key"), "PHI_ENCRYPTION_KEY");

        // PHI_ENCRYPTION_KEY_V1 .. PHI_ENCRYPTION_KEY_V<MAX_VERSION_PROBE>.
        for (int v = 1; v <= MAX_VERSION_PROBE; v++) {
            String prop = environment.getProperty("phi.encryption.key.v" + v);
            addKeyIfConfigured(v, prop, "PHI_ENCRYPTION_KEY_V" + v);
        }

        if (keysByVersion.isEmpty()) {
            if (isProductionProfile()) {
                throw new IllegalStateException(
                        "No PHI encryption keys configured. Set PHI_ENCRYPTION_KEY in the " +
                        "production profile. Generate with: openssl rand -base64 32");
            }
            log.warn("No PHI encryption keys configured — PHI columns will be stored in " +
                    "PLAINTEXT. Acceptable for local H2 dev only.");
            currentVersion = 0; // sentinel for "no encryption"
            return;
        }

        currentVersion = Collections.max(keysByVersion.keySet());
        log.info("PHI encryptor initialised. Configured versions: {} (current for writes: v{})",
                keysByVersion.keySet(), currentVersion);
    }

    private void addKeyIfConfigured(int version, String keyB64, String envVarName) {
        if (keyB64 == null || keyB64.isBlank()) return;

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(keyB64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(envVarName + " is not valid base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    envVarName + " must decode to exactly 32 bytes (AES-256). " +
                    "Got " + decoded.length + " bytes.");
        }

        SecretKey newKey = new SecretKeySpec(decoded, "AES");
        SecretKey existing = keysByVersion.get(version);
        if (existing != null) {
            if (!Arrays.equals(existing.getEncoded(), newKey.getEncoded())) {
                throw new IllegalStateException(
                        "Conflicting keys configured for v" + version + ". " +
                        "Don't set both PHI_ENCRYPTION_KEY and PHI_ENCRYPTION_KEY_V1 " +
                        "with different values.");
            }
            // Same key configured twice (legitimate when both PHI_ENCRYPTION_KEY
            // and PHI_ENCRYPTION_KEY_V1 are set to the same value). Silent no-op.
            return;
        }
        keysByVersion.put(version, newKey);
        log.info("Loaded PHI key v{} (id={}) from {}", version, keyFingerprint(decoded), envVarName);
    }

    /**
     * Encrypt with the current version key. Null and empty strings pass through.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        SecretKey key = keysByVersion.get(currentVersion);
        if (key == null) {
            // No key configured — dev-only pass-through.
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

            return "{enc:v" + currentVersion + "}" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("PHI encryption failed", e);
        }
    }

    /**
     * Decrypt a value read from storage. Strings without a {@code {enc:v<N>}}
     * prefix are treated as legacy plaintext and returned as-is.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        Matcher prefix = PREFIX_PATTERN.matcher(stored);
        if (!prefix.find()) {
            // Legacy plaintext (or already-decrypted). Pass through.
            return stored;
        }

        int version = Integer.parseInt(prefix.group(1));
        SecretKey key = keysByVersion.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    "Encrypted value found with version v" + version +
                    " but no key configured for that version. " +
                    "Restore PHI_ENCRYPTION_KEY_V" + version + " (or PHI_ENCRYPTION_KEY for v1).");
        }

        try {
            String body = stored.substring(prefix.end());
            byte[] combined = Base64.getDecoder().decode(body);
            if (combined.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalStateException("Ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "PHI decryption failed for v" + version + " (key mismatch or corruption?)", e);
        }
    }

    /** Whether the stored value is encrypted (has a {enc:v<N>} prefix). */
    public boolean isEncrypted(String stored) {
        return stored != null && PREFIX_PATTERN.matcher(stored).find();
    }

    /** Whether the stored value is encrypted under a version older than {@code currentVersion}. */
    public boolean isLegacyVersion(String stored) {
        if (stored == null) return false;
        Matcher m = PREFIX_PATTERN.matcher(stored);
        if (!m.find()) return false;
        return Integer.parseInt(m.group(1)) < currentVersion;
    }

    private boolean isProductionProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

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
}
