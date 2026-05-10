-- =============================================================================
-- V6: widen PHI String columns to fit AES-256-GCM ciphertext.
--
-- New writes go through EncryptedStringConverter and produce values of the form
--   {enc:v1}<base64(IV || ciphertext || GCM tag)>
-- For typical-length PHI (names, emails, phone numbers) this never exceeds
-- ~500 chars, but the original schema sized these as VARCHAR(100) / VARCHAR(255)
-- which would silently truncate ciphertext and corrupt the row.
--
-- Existing plaintext rows continue to fit (and continue to decrypt to themselves
-- via the {enc:} prefix check in PhiEncryptor) — no data migration required here.
-- =============================================================================
ALTER TABLE patients ALTER COLUMN name TYPE VARCHAR(500);
ALTER TABLE doctors  ALTER COLUMN full_name TYPE VARCHAR(500);
ALTER TABLE doctors  ALTER COLUMN email TYPE VARCHAR(500);
ALTER TABLE doctors  ALTER COLUMN phone TYPE VARCHAR(500);
