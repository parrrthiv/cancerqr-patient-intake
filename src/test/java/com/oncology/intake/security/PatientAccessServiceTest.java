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
 * Verifies the IDOR-prevention rules in {@link PatientAccessService}.
 *
 * <p>Every case here corresponds to a behaviour the dashboard currently relies on
 * to keep doctors out of each other's patients. A regression in
 * {@code PatientAccessService} is the most likely way to silently re-open the
 * IDOR holes that PRs 3 and 5 closed.
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
            Doctor admin = doctor(PhysicianDomain.ADMIN);
            Patient p = patientWithReferringDoctor(doctor(PhysicianDomain.REFERRING_DOCTOR));

            assertTrue(service.canViewPatient(admin, p));
        }

        @Test
        @DisplayName("REFERRING_DOCTOR sees only their own patients")
        void referringDoctorSeesOwnOnly() {
            Doctor me = doctor(PhysicianDomain.REFERRING_DOCTOR);
            Doctor other = doctor(PhysicianDomain.REFERRING_DOCTOR);

            assertTrue(service.canViewPatient(me, patientWithReferringDoctor(me)),
                    "Doctor sees their own patient");
            assertFalse(service.canViewPatient(me, patientWithReferringDoctor(other)),
                    "Doctor cannot see another doctor's patient");
            assertFalse(service.canViewPatient(me, patientWithReferringDoctor(null)),
                    "Doctor cannot see patient with no referring doctor recorded");
        }

        @Test
        @DisplayName("tumor-board domain sees patients with at least one review on file")
        void tumorBoardSeesPatientsInQueue() {
            Doctor medicalOnc = doctor(PhysicianDomain.MEDICAL_ONCOLOGY);
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId()))
                    .thenReturn(List.of(new TumorBoardReview()));

            assertTrue(service.canViewPatient(medicalOnc, p));
        }

        @Test
        @DisplayName("tumor-board domain cannot see patients with zero reviews")
        void tumorBoardCannotSeeUnreviewedPatient() {
            Doctor radOnc = doctor(PhysicianDomain.RADIATION_ONCOLOGY);
            Patient p = patient();
            when(reviewRepository.findByPatientId(p.getId())).thenReturn(List.of());

            assertFalse(service.canViewPatient(radOnc, p));
        }

        @Test
        @DisplayName("null doctor or null patient denies access")
        void nullsDeny() {
            assertFalse(service.canViewPatient(null, patient()));
            assertFalse(service.canViewPatient(doctor(PhysicianDomain.ADMIN), null));
            assertFalse(service.canViewPatient(null, null));
        }
    }

    @Nested
    @DisplayName("canViewReport")
    class CanViewReport {

        @Test
        @DisplayName("delegates to canViewPatient via report.patient")
        void delegatesToPatient() {
            Doctor admin = doctor(PhysicianDomain.ADMIN);
            Patient p = patient();
            Report r = new Report();
            r.setPatient(p);

            assertTrue(service.canViewReport(admin, r));
        }

        @Test
        @DisplayName("null report denies access")
        void nullReportDenies() {
            assertFalse(service.canViewReport(doctor(PhysicianDomain.ADMIN), null));
        }

        @Test
        @DisplayName("report with null patient denies access")
        void reportWithoutPatientDenies() {
            Report orphan = new Report();
            assertFalse(service.canViewReport(doctor(PhysicianDomain.ADMIN), orphan));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Doctor doctor(PhysicianDomain domain) {
        return Doctor.builder().id(UUID.randomUUID()).domain(domain).build();
    }

    private Patient patient() {
        return Patient.builder().id(UUID.randomUUID()).build();
    }

    private Patient patientWithReferringDoctor(Doctor referring) {
        return Patient.builder().id(UUID.randomUUID()).referringDoctor(referring).build();
    }
}
