package com.oncology.intake.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Deterministic hash of a WhatsApp number for equality lookups.
 *
 * <h2>Why this exists</h2>
 * The plaintext {@code Patient.whatsappNumber} column is encrypted with
 * AES-256-GCM and a fresh random IV per write (see {@link PhiEncryptor}).
 * That makes equality queries on the encrypted column impossible — the same
 * phone number produces different ciphertext every time.
 *
 * <p>To preserve the {@code findByWhatsappNumber} hot path on every inbound
 * webhook, we store a deterministic HMAC-SHA256 of the normalised phone number
 * alongside the encrypted column. Lookups join on the hash; the encrypted
 * column carries the actual value for display.
 *
 * <h2>Key separation</h2>
 * The HMAC key ({@code PHI_HMAC_KEY}) is intentionally <em>separate</em> from
 * the encryption key ({@code PHI_ENCRYPTION_KEY}). Rationale:
 * <ul>
 *   <li>An attacker with read access to the DB sees both encrypted bytes and
 *       hash values. If the same key produced both, knowledge of one
 *       compromises the other.</li>
 *   <li>The two keys have different recovery profiles — losing the encryption
 *       key is permanent data loss; losing the HMAC key is recoverable by
 *       re-hashing decrypted values under a new key.</li>
 *   <li>Rotation cadences may differ.</li>
 * </ul>
 *
 * <h2>Normalisation</h2>
 * Inputs are normalised before hashing so that {@code "+91 98765 43210"},
 * {@code "919876543210"}, and {@code "+919876543210"} all produce the same
 * hash. Without this, the same human shows up as multiple patients.
 *
 * <h2>Dev fallback</h2>
 * If {@code PHI_HMAC_KEY} is not configured (typical local dev), the hasher
 * returns the normalised number itself — equality lookups still work, no real
 * cryptographic protection. Production fails fast on a missing key.
 *
 * <h2>Failure to recover from a lost HMAC key</h2>
 * If the key is lost and replaced, existing rows' hash columns are stale and
 * cannot be looked up by their original phone numbers. Recovery: decrypt each
 * row's {@code whatsappNumber} (which uses the unchanged encryption key),
 * re-hash with the new HMAC key, UPDATE. Painful but not destructive.
 */
@Service
@Slf4j
public class WhatsAppNumberHasher {

    private final Environment environment;
    private final String configuredKeyB64;

    /** Resolved HMAC key. Null when no key is configured (dev fallback). */
    private SecretKey key;

    public WhatsAppNumberHasher(Environment environment,
                                @Value("${phi.hmac.key:}") String configuredKeyB64) {
        this.environment = environment;
        this.configuredKeyB64 = configuredKeyB64;
    }

    @PostConstruct
    void initialise() {
        if (configuredKeyB64 == null || configuredKeyB64.isBlank()) {
            if (isProductionProfile()) {
                throw new IllegalStateException(
                        "PHI_HMAC_KEY must be set in the production profile. " +
                        "Generate with: openssl rand -base64 32");
            }
            log.warn("PHI_HMAC_KEY not set — WhatsApp number lookups will use normalised " +
                    "plaintext as the hash. Acceptable for local H2 dev only.");
            return;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKeyB64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PHI_HMAC_KEY is not valid base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "PHI_HMAC_KEY must decode to exactly 32 bytes. " +
                    "Got " + decoded.length + " bytes. Generate with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(decoded, "HmacSHA256");
        log.info("WhatsApp number hasher initialised (HMAC-SHA256, key id={})",
                keyFingerprint(decoded));
    }

    /**
     * Hash a WhatsApp number for storage in {@code patients.whatsapp_number_hash}
     * or for use as a query key in {@code findByWhatsappNumberHash}.
     */
    public String hash(String rawNumber) {
        if (rawNumber == null) {
            return null;
        }
        String normalised = normalise(rawNumber);

        if (key == null) {
            // Dev fallback: no key, no real hashing. Lookups still work because
            // both the persist path and the query path normalise identically.
            return normalised;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] hmac = mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8));
            // 32 raw bytes → 44 base64 chars. Comfortably fits VARCHAR(64).
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("WhatsApp number hashing failed", e);
        }
    }

    /**
     * Canonical form of a phone number: digits only, leading {@code +} stripped,
     * whitespace and punctuation removed. Same human, same hash, regardless of
     * how the number was entered.
     *
     * <p>Public so callers that store the plaintext (encrypted) value can
     * normalise too — otherwise the encrypted column drifts from the hash.
     */
    public static String normalise(String rawNumber) {
        if (rawNumber == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(rawNumber.length());
        for (int i = 0; i < rawNumber.length(); i++) {
            char c = rawNumber.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
            // Skip everything else: '+', '-', '(', ')', '.', whitespace, etc.
        }
        return out.toString();
    }

    private boolean isProductionProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    /** First 4 bytes of SHA-256(key), as hex. Identifies the key without logging it. */
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
