package com.oncology.intake.security;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Report;
import com.oncology.intake.repository.TumorBoardReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for "can this doctor see this patient / report?".
 *
 * Access policy (capability-union — a doctor may hold several):
 *  - {@code ADMIN} (sys-admin) or any {@code canFinalize} doctor — sees everything.
 *  - {@code canReview} — sees patients that have a tumor board review on file
 *    (i.e. patients formally submitted to the board).
 *  - {@code canIntake} — sees only the patients they personally intook
 *    (referringDoctor == them).
 *
 * Without these checks, any logged-in doctor could fetch any patient by UUID,
 * and any logged-in doctor could fetch any report by UUID — regardless of
 * whether the patient is even part of the board's case load. This service
 * closes that hole.
 *
 * Call once per request from controllers; do not cache results across requests.
 */
@Service
@RequiredArgsConstructor
public class PatientAccessService {

    private final TumorBoardReviewRepository reviewRepository;

    public boolean canViewPatient(Doctor doctor, Patient patient) {
        if (doctor == null || patient == null) {
            return false;
        }

        // Capability-union (PR: doctor capabilities). A doctor may hold several
        // capabilities; visibility is the union of what each grants.

        // Sys-admin and any finalize-capable doctor oversee every case.
        if (doctor.getDomain() == PhysicianDomain.ADMIN
                || Boolean.TRUE.equals(doctor.getCanFinalize())) {
            return true;
        }
        // Reviewers see patients that are on the tumor board (have a review on file).
        if (Boolean.TRUE.equals(doctor.getCanReview())
                && !reviewRepository.findByPatientId(patient.getId()).isEmpty()) {
            return true;
        }
        // Intake doctors see the patients they themselves intook.
        if (Boolean.TRUE.equals(doctor.getCanIntake())
                && patient.getReferringDoctor() != null
                && doctor.getId().equals(patient.getReferringDoctor().getId())) {
            return true;
        }
        return false;
    }

    public boolean canViewReport(Doctor doctor, Report report) {
        if (report == null || report.getPatient() == null) {
            return false;
        }
        if (!canViewPatient(doctor, report.getPatient())) {
            return false;
        }
        // PHI redaction gate (PR 13): only ADMIN may open a report that hasn't
        // been cleared. Every other role — including the tumor-board domains —
        // sees a report only once an admin marks it APPROVED. PENDING and
        // REDACTION_NEEDED files stay hidden so un-reviewed or flagged PHI is
        // never served to reviewers, even if they know the report's URL.
        if (doctor.getDomain() == PhysicianDomain.ADMIN) {
            return true;
        }
        return report.getPhiReviewStatus() == Report.PhiReviewStatus.APPROVED;
    }
}
