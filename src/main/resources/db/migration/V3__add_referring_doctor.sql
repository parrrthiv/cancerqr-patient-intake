ALTER TABLE doctors ADD COLUMN referral_code VARCHAR(20) UNIQUE;
ALTER TABLE patients ADD COLUMN referring_doctor_id UUID REFERENCES doctors(id);
CREATE INDEX idx_patients_referring_doctor ON patients(referring_doctor_id);
