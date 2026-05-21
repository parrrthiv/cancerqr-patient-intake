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
 * Access policy:
 *  - {@code ADMIN}             — sees everything.
 *  - {@code REFERRING_DOCTOR}  — sees only patients they personally referred.
 *  - any of the 8 tumor-board domains — sees only patients that have a tumor
 *    board review on file (i.e. patients formally submitted to the board).
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

        PhysicianDomain domain = doctor.getDomain();
        if (domain == null) {
            return false;
        }

        return switch (domain) {
            case ADMIN -> true;
            case REFERRING_DOCTOR -> patient.getReferringDoctor() != null
                    && doctor.getId().equals(patient.getReferringDoctor().getId());
            // The 8 tumor-board domains: see patients in the board queue only.
            default -> !reviewRepository.findByPatientId(patient.getId()).isEmpty();
        };
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
