-- =============================================================================
-- V5: index on patients.referring_doctor_id.
--
-- Both DashboardController.dashboard() and DashboardController.allPatients()
-- call patientRepository.findByReferringDoctorId(doctorId) — without this
-- index that's a sequential scan on the patients table for every dashboard
-- load by every referring doctor.
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_patients_referring_doctor_id
    ON patients (referring_doctor_id);
