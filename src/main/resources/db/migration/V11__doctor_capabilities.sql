-- V11: Doctor capability flags
--
-- Decouples three capabilities from the single rigid PhysicianDomain role so one
-- account can review + finalize + intake. A "Doctor" is a tumor-board physician
-- with finalize + intake; a "medicine participant" is a review-only integrative
-- reviewer; ADMIN remains the separate sys-admin (accounts + PHI review).
--
--   can_review   — sits on the tumor board for `domain`
--   can_intake   — may perform patient intake (formerly REFERRING_DOCTOR)
--   can_finalize — may approve/finalize the final protocol (formerly ADMIN-only)

ALTER TABLE doctors ADD COLUMN can_intake   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE doctors ADD COLUMN can_finalize BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE doctors ADD COLUMN can_review   BOOLEAN NOT NULL DEFAULT false;

-- Backfill from the existing single-role model so nobody loses access at cutover.

-- All 8 specialty domains review.
UPDATE doctors SET can_review = true
 WHERE domain IN ('MEDICAL_ONCOLOGY','SURGICAL_ONCOLOGY','RADIATION_ONCOLOGY',
                  'PRECISION_ONCOLOGY','PALLIATIVE_CARE','AYURVEDA_INTEGRATIVE',
                  'FUNCTIONAL_MEDICINE','DIETICIAN_NUTRITION');

-- Physician oncology domains are "Doctors": also finalize + intake.
UPDATE doctors SET can_finalize = true, can_intake = true
 WHERE domain IN ('MEDICAL_ONCOLOGY','SURGICAL_ONCOLOGY','RADIATION_ONCOLOGY',
                  'PRECISION_ONCOLOGY','PALLIATIVE_CARE');
-- (Integrative domains DIETICIAN_NUTRITION / AYURVEDA_INTEGRATIVE /
--  FUNCTIONAL_MEDICINE stay review-only = medicine participants.)

-- Referring doctors become intake-only.
UPDATE doctors SET can_intake = true WHERE domain = 'REFERRING_DOCTOR';

-- ADMIN keeps finalize so protocol approval keeps working through the cutover;
-- ADMIN remains the sys-admin (manage doctors + PHI review) regardless.
UPDATE doctors SET can_finalize = true WHERE domain = 'ADMIN';
