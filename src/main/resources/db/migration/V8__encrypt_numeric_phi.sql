-- =============================================================================
-- V8: Encrypt numeric PHI columns on the patients table.
--
-- Columns: weight_kg, pain_scale, effective_pain_scale, esr_value, crp_value,
--          cancer_stage.
--
-- These columns hold clinical values that, in combination with other patient
-- attributes, are identifying PHI (see HIPAA Safe Harbor and DPDPA health-data
-- treatment). Encrypting them with the existing AES-256-GCM scheme via the new
-- EncryptedBigDecimalConverter and EncryptedIntegerConverter.
--
-- The column types must change from NUMERIC/INTEGER to VARCHAR to hold ciphertext.
-- Any existing rows (post-V7 wipe + any rows added after V7) are converted to
-- text via the USING clause; the encryption-aware reader sees these as legacy
-- plaintext (no {enc:v1} prefix), parses normally, and the next save re-encrypts.
-- =============================================================================

ALTER TABLE patients
    ALTER COLUMN weight_kg            TYPE VARCHAR(500) USING weight_kg::TEXT;

ALTER TABLE patients
    ALTER COLUMN pain_scale           TYPE VARCHAR(500) USING pain_scale::TEXT;

ALTER TABLE patients
    ALTER COLUMN effective_pain_scale TYPE VARCHAR(500) USING effective_pain_scale::TEXT;

ALTER TABLE patients
    ALTER COLUMN esr_value            TYPE VARCHAR(500) USING esr_value::TEXT;

ALTER TABLE patients
    ALTER COLUMN crp_value            TYPE VARCHAR(500) USING crp_value::TEXT;

-- cancer_stage was already VARCHAR(50); just widen for ciphertext.
ALTER TABLE patients
    ALTER COLUMN cancer_stage         TYPE VARCHAR(500);
