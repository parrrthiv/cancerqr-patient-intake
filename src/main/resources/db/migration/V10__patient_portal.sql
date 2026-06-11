-- V10: Patient portal (web access for patients, no WhatsApp required)
--
--  patient_accounts  — login credentials for the patient-facing portal.
--      One account per patient. Login identifier is the phone number,
--      stored encrypted (AES-GCM via EncryptedStringConverter) with a
--      deterministic HMAC-SHA256 hash column for lookups — the exact same
--      pattern as patients.whatsapp_number / whatsapp_number_hash.
--      Password is BCrypt via the DelegatingPasswordEncoder (never PHI-encrypted;
--      BCrypt is its own one-way protection).
--
--      Account-takeover guard: registering a number that already has a patient
--      record (created via WhatsApp) requires proving ownership of that number
--      with a one-time code delivered over WhatsApp. Until verified the account
--      row exists but enabled = FALSE, so it cannot log in. otp_hash stores
--      SHA-256(code), never the code itself.
--
--  patient_messages  — messages from the care team (doctor → patient).
--      Body is PHI (clinical content) and is therefore stored encrypted;
--      column is sized VARCHAR(4000) to hold ciphertext+base64 overhead for
--      a ~1000-char plaintext message (cap enforced in the service layer).

CREATE TABLE patient_accounts (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL UNIQUE REFERENCES patients(id),
    phone_hash VARCHAR(64) NOT NULL UNIQUE,
    phone VARCHAR(500) NOT NULL,
    password VARCHAR(255) NOT NULL,
    display_name VARCHAR(500),
    enabled BOOLEAN NOT NULL,
    phone_verified BOOLEAN NOT NULL,
    otp_hash VARCHAR(64),
    otp_expires_at TIMESTAMP,
    otp_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP
);

CREATE INDEX idx_patient_accounts_patient_id ON patient_accounts (patient_id);

CREATE TABLE patient_messages (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    doctor_id UUID REFERENCES doctors(id),
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);

CREATE INDEX idx_patient_messages_patient_id ON patient_messages (patient_id);
CREATE INDEX idx_patient_messages_unread ON patient_messages (patient_id, read_at);
