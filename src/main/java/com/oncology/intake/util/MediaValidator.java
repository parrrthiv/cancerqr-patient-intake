package com.oncology.intake.util;

import java.util.Locale;
import java.util.Set;

/**
 * Validates uploaded media before it reaches storage.
 *
 * Three checks, in order:
 *  1. Size: rejects empty files and anything over the configured cap.
 *  2. Whitelist: declared content type must be one of the four allowed.
 *  3. Magic bytes: the file's leading bytes must actually match the
 *     declared type. This is what stops a malicious uploader from sending
 *     {@code text/html} bytes labelled {@code application/pdf} (and vice
 *     versa); the download endpoint serves with {@code nosniff} +
 *     {@code attachment} as defence-in-depth, but the right place to refuse
 *     a poisoned upload is here, before it lands in S3.
 *
 * Callers should treat {@link InvalidMediaException} as a 4xx-class error
 * for the patient (the WhatsApp flow tells them to send a JPG/PNG/PDF).
 *
 * Do NOT trust the WhatsApp-supplied content type without this validator —
 * Meta forwards whatever the sender claimed.
 */
public final class MediaValidator {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    private MediaValidator() {}

    public static void validate(byte[] content, String declaredContentType, long maxSizeBytes) {
        if (content == null || content.length == 0) {
            throw new InvalidMediaException("Empty file");
        }
        if (content.length > maxSizeBytes) {
            throw new InvalidMediaException(
                    "File too large: " + content.length + " bytes (max " + maxSizeBytes + ")");
        }

        String declared = normalizeContentType(declaredContentType);
        if (!ALLOWED_CONTENT_TYPES.contains(declared)) {
            throw new InvalidMediaException(
                    "Unsupported content type: '" + declaredContentType + "'. " +
                    "Allowed: " + ALLOWED_CONTENT_TYPES);
        }

        String detected = detectFromMagicBytes(content);
        if (detected == null) {
            throw new InvalidMediaException(
                    "Could not identify file type from content. " +
                    "Declared: " + declared);
        }
        if (!detected.equals(declared)) {
            throw new InvalidMediaException(
                    "Content type mismatch: declared '" + declared +
                    "' but bytes look like '" + detected + "'");
        }
    }

    /** Strips parameters (e.g. {@code ; charset=utf-8}) and lowercases. */
    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        int sep = contentType.indexOf(';');
        String base = (sep >= 0) ? contentType.substring(0, sep) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Inspects the first 12 bytes of the payload and returns the canonical
     * MIME type, or {@code null} if no signature matches.
     */
    private static String detectFromMagicBytes(byte[] b) {
        if (b.length < 4) return null;

        // JPEG: FF D8 FF
        if (u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF) {
            return "image/jpeg";
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b.length >= 8
                && u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && u(b[4]) == 0x0D && u(b[5]) == 0x0A
                && u(b[6]) == 0x1A && u(b[7]) == 0x0A) {
            return "image/png";
        }

        // WebP: 'RIFF' .... 'WEBP'
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }

        // PDF: %PDF
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }

        return null;
    }

    private static int u(byte v) {
        return v & 0xFF;
    }

    public static class InvalidMediaException extends RuntimeException {
        public InvalidMediaException(String message) {
            super(message);
        }
    }
}
