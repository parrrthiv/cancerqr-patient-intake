package com.oncology.intake.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the file-based validator used by the streaming upload path keeps the
 * same security guarantees as the byte[] version: size bounds + MIME whitelist +
 * magic-byte match, reading only the header off disk.
 */
@DisplayName("MediaValidator.validate(Path) — file-based streaming validation")
class MediaValidatorFileTest {

    private static final long MAX = 25L * 1024 * 1024;
    private final List<Path> temps = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Path p : temps) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
        }
    }

    private Path tmp(byte[] bytes) throws IOException {
        Path p = Files.createTempFile("mv-test-", ".bin");
        Files.write(p, bytes);
        temps.add(p);
        return p;
    }

    private static byte[] pdf(int len) {
        byte[] b = new byte[Math.max(len, 4)];
        b[0] = '%'; b[1] = 'P'; b[2] = 'D'; b[3] = 'F';
        return b;
    }

    private static byte[] png(int len) {
        byte[] b = new byte[Math.max(len, 8)];
        b[0] = (byte) 0x89; b[1] = 'P'; b[2] = 'N'; b[3] = 'G';
        b[4] = 0x0D; b[5] = 0x0A; b[6] = 0x1A; b[7] = 0x0A;
        return b;
    }

    @Test
    @DisplayName("valid PDF passes")
    void validPdf() throws IOException {
        Path f = tmp(pdf(2048));
        assertDoesNotThrow(() -> MediaValidator.validate(f, "application/pdf", MAX));
    }

    @Test
    @DisplayName("valid PNG passes")
    void validPng() throws IOException {
        Path f = tmp(png(2048));
        assertDoesNotThrow(() -> MediaValidator.validate(f, "image/png", MAX));
    }

    @Test
    @DisplayName("declared PDF but PNG bytes is rejected (magic mismatch)")
    void mismatchRejected() throws IOException {
        Path f = tmp(png(2048));
        assertThrows(MediaValidator.InvalidMediaException.class,
                () -> MediaValidator.validate(f, "application/pdf", MAX));
    }

    @Test
    @DisplayName("empty file is rejected")
    void emptyRejected() throws IOException {
        Path f = tmp(new byte[0]);
        assertThrows(MediaValidator.InvalidMediaException.class,
                () -> MediaValidator.validate(f, "application/pdf", MAX));
    }

    @Test
    @DisplayName("oversized file is rejected")
    void oversizedRejected() throws IOException {
        Path f = tmp(pdf(2048));
        assertThrows(MediaValidator.InvalidMediaException.class,
                () -> MediaValidator.validate(f, "application/pdf", 100));
    }

    @Test
    @DisplayName("disallowed content type is rejected")
    void disallowedTypeRejected() throws IOException {
        Path f = tmp(pdf(2048));
        assertThrows(MediaValidator.InvalidMediaException.class,
                () -> MediaValidator.validate(f, "text/html", MAX));
    }

    @Test
    @DisplayName("unrecognized bytes are rejected")
    void unrecognizedBytesRejected() throws IOException {
        Path f = tmp(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(MediaValidator.InvalidMediaException.class,
                () -> MediaValidator.validate(f, "application/pdf", MAX));
    }
}
