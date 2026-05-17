-- =============================================================================
-- V9: Add PHI redaction review workflow columns to reports.
--
-- Lab reports and scans uploaded by patients frequently contain identifying
-- information (name, MRN, hospital address) in their headers and footers.
-- Stage 1 of the redaction workflow is human review by an admin. Stage 2
-- (automated detection via Textract + Comprehend Medical) is a future PR.
--
-- New uploads start as PENDING. An admin must mark each file APPROVED or
-- REDACTION_NEEDED via /dashboard/reports/phi-review.
-- =============================================================================

ALTER TABLE reports
    ADD COLUMN phi_review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';

ALTER TABLE reports
    ADD COLUMN phi_reviewed_by_doctor_id UUID;

ALTER TABLE reports
    ADD COLUMN phi_reviewed_at TIMESTAMP;

CREATE INDEX idx_phi_review_status ON reports (phi_review_status);
