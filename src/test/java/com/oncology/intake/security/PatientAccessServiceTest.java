package com.oncology.intake.security;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.TumorBoardReview;
import com.oncology.intake.repository.TumorBoardReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Verifies the IDOR-prevention rules in {@link PatientAccessService} under the
 * capability model (PR: doctor capabilities): visibility is the UNION of what a
 * doctor's capabilities grant —
 *  - ADMIN (sys-admin) or canFinalize → all patients,
 *  - canReview → patients with a tumor-board review on file,
 *  - canIntake → only patients they themselves intook.
 *
 * <p>A regression here is the most likely way to silently re-open the IDOR holes
 * that PRs 3/5 closed, so each capability path is pinned.
 */
@ExtendWith(MockitoExtension.class)
class PatientAccessServiceTest {

    @Mock private TumorBoardReviewRepository reviewRepository;

    @InjectMocks private PatientAccessService service;

    @Nested
    @DisplayName("canViewPatient")
    class CanViewPatient {

        @Test
        @DisplayName("ADMIN sees every patient")
        void adminSeesAll() {
            Patient p = patientWithReferringDoctor(intakeOnly());
            assertTrue(service.canViewPatient(admin(), p));
        }

        @Test
        @DisplayName("a finalize-capable doctor sees every patient (even with no reviews)")
        void finalizerSeesAll() {
            // physician() has canFinalize=true; patient has no reviews → still visible.
            assertTrue(service.canViewPatient(physician(), patient()));
        }

        @Test
        @DisplayName("a reviewer (canReview) sees patients that have a review on file")
        void reviewerSeesInQueue() {
            Doctor reviewer = reviewer();
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId()))
                    .thenReturn(List.of(new TumorBoardReview()));
            assertTrue(service.canViewPatient(reviewer, p));
        }

        @Test
        @DisplayName("a reviewer cannot see patients with zero reviews")
        void reviewerCannotSeeUnreviewed() {
            Doctor reviewer = reviewer();
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId())).thenReturn(List.of());
            assertFalse(service.canViewPatient(reviewer, p));
        }

        @Test
        @DisplayName("an intake-only doctor sees only the patients they intook")
        void intakeDoctorSeesOwnOnly() {
            Doctor me = intakeOnly();
            Doctor other = intakeOnly();
            assertTrue(service.canViewPatient(me, patientWithReferringDoctor(me)),
                    "sees their own intook patient");
            assertFalse(service.canViewPatient(me, patientWithReferringDoctor(other)),
                    "cannot see another doctor's patient");
            assertFalse(service.canViewPatient(me, patientWithReferringDoctor(null)),
                    "cannot see a patient with no referring doctor recorded");
        }

        @Test
        @DisplayName("a doctor with no capabilities sees nothing")
        void noCapabilitiesSeesNothing() {
            Doctor none = doctor(PhysicianDomain.MEDICAL_ONCOLOGY); // all flags false
            assertFalse(service.canViewPatient(none, patientWithReferringDoctor(none)));
        }

        @Test
        @DisplayName("null doctor or null patient denies access")
        void nullsDeny() {
            assertFalse(service.canViewPatient(null, patient()));
            assertFalse(service.canViewPatient(admin(), null));
            assertFalse(service.canViewPatient(null, null));
        }
    }

    @Nested
    @DisplayName("canViewReport")
    class CanViewReport {

        @Test
        @DisplayName("delegates to canViewPatient via report.patient")
        void delegatesToPatient() {
            Report r = new Report();
            r.setPatient(patient());
            assertTrue(service.canViewReport(admin(), r));
        }

        @Test
        @DisplayName("null report denies access")
        void nullReportDenies() {
            assertFalse(service.canViewReport(admin(), null));
        }

        @Test
        @DisplayName("report with null patient denies access")
        void reportWithoutPatientDenies() {
            assertFalse(service.canViewReport(admin(), new Report()));
        }

        @Test
        @DisplayName("reviewer sees an APPROVED report for an in-queue patient")
        void reviewerSeesApprovedReport() {
            Doctor reviewer = reviewer();
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId()))
                    .thenReturn(List.of(new TumorBoardReview()));
            assertTrue(service.canViewReport(reviewer, report(p, Report.PhiReviewStatus.APPROVED)));
        }

        @Test
        @DisplayName("reviewer cannot open a PENDING report even for an in-queue patient")
        void reviewerDeniedPendingReport() {
            Doctor reviewer = reviewer();
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId()))
                    .thenReturn(List.of(new TumorBoardReview()));
            assertFalse(service.canViewReport(reviewer, report(p, Report.PhiReviewStatus.PENDING)));
        }

        @Test
        @DisplayName("reviewer cannot open a REDACTION_NEEDED report")
        void reviewerDeniedRedactionNeededReport() {
            Doctor reviewer = reviewer();
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId()))
                    .thenReturn(List.of(new TumorBoardReview()));
            assertFalse(service.canViewReport(reviewer, report(p, Report.PhiReviewStatus.REDACTION_NEEDED)));
        }

        @Test
        @DisplayName("ADMIN can open a report regardless of PHI review status")
        void adminSeesUnapprovedReport() {
            assertTrue(service.canViewReport(admin(), report(patient(), Report.PhiReviewStatus.PENDING)));
        }
    }

    // ── helpers (Builder.Default leaves unset capability flags false) ────────

    /** Bare doctor with a domain and NO capabilities. */
    private Doctor doctor(PhysicianDomain domain) {
        return Doctor.builder().id(UUID.randomUUID()).domain(domain).build();
    }

    /** Sys-admin: finalize capability, no review/intake. */
    private Doctor admin() {
        return Doctor.builder().id(UUID.randomUUID())
                .domain(PhysicianDomain.ADMIN).canFinalize(true).build();
    }

    /** Full physician Doctor: review + intake + finalize. */
    private Doctor physician() {
        return Doctor.builder().id(UUID.randomUUID())
                .domain(PhysicianDomain.MEDICAL_ONCOLOGY)
                .canReview(true).canIntake(true).canFinalize(true).build();
    }

    /** Medicine participant: review-only. */
    private Doctor reviewer() {
        return Doctor.builder().id(UUID.randomUUID())
                .domain(PhysicianDomain.DIETICIAN_NUTRITION).canReview(true).build();
    }

    /** Referring-style: intake-only. */
    private Doctor intakeOnly() {
        return Doctor.builder().id(UUID.randomUUID())
                .domain(PhysicianDomain.REFERRING_DOCTOR).canIntake(true).build();
    }

    private Patient patient() {
        return Patient.builder().id(UUID.randomUUID()).build();
    }

    private Patient patientWithReferringDoctor(Doctor referring) {
        return Patient.builder().id(UUID.randomUUID()).referringDoctor(referring).build();
    }

    private Report report(Patient patient, Report.PhiReviewStatus status) {
        Report r = new Report();
        r.setPatient(patient);
        r.setPhiReviewStatus(status);
        return r;
    }
}
