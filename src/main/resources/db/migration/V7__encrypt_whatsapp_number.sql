-- =============================================================================
-- V7: Encrypt patients.whatsapp_number with companion HMAC lookup column.
--
-- Per operator decision (pre-production, test data only): clear all existing
-- patient-related rows rather than implementing a backfill. New writes go
-- through the encrypt + hash path.
--
-- After this migration:
--   patients.whatsapp_number       -- AES-256-GCM ciphertext (PhiEncryptor)
--   patients.whatsapp_number_hash  -- HMAC-SHA256(normalised number)
-- Lookups use the hash column. The encrypted column carries the actual
-- display value. The plaintext column had a unique constraint; the hash
-- column inherits that responsibility.
-- =============================================================================

-- 1. Wipe patient-related data (child tables first due to FK constraints).
--    audit_logs has no FK on patient_id, so its rows are retained as a
--    historical record of pre-V7 activity.
DELETE FROM final_protocols;
DELETE FROM tumor_board_reviews;
DELETE FROM analyses;
DELETE FROM reports;
DELETE FROM patients;

-- 2. Drop the unique constraint and explicit index on the plaintext column.
--    Encrypted writes use a random IV per row, so no two ciphertexts are
--    equal even for the same plaintext — uniqueness here is meaningless
--    and would actively break inserts. Uniqueness moves to the hash column.
ALTER TABLE patients DROP CONSTRAINT IF EXISTS patients_whatsapp_number_key;
DROP INDEX IF EXISTS idx_whatsapp_number;

-- 3. Widen the column for AES-GCM ciphertext.
--    Encrypted form: '{enc:v1}' + base64(IV || ciphertext || tag), ~80 chars
--    for a typical 12-digit number plus comfortable headroom.
ALTER TABLE patients ALTER COLUMN whatsapp_number TYPE VARCHAR(500);

-- 4. Add the HMAC-SHA256 lookup column.
--    HMAC-SHA256 → 32 bytes → 44 chars base64. 64 is comfortable headroom.
--    Required (NOT NULL) since every patient must be findable on inbound webhook;
--    no DEFAULT because we just wiped the table.
ALTER TABLE patients ADD COLUMN whatsapp_number_hash VARCHAR(64) NOT NULL;

-- 5. Unique index on the hash for the findByWhatsappNumberHash hot path.
CREATE UNIQUE INDEX idx_patients_whatsapp_number_hash
    ON patients (whatsapp_number_hash);
