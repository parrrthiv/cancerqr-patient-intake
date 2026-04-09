ALTER TABLE patients ADD COLUMN cancer_stage VARCHAR(50);
ALTER TABLE patients ADD COLUMN esr_value NUMERIC(6, 2);
ALTER TABLE patients ADD COLUMN crp_value NUMERIC(6, 2);
ALTER TABLE patients ADD COLUMN effective_pain_scale INTEGER;
