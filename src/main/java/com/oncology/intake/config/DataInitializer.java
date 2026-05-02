package com.oncology.intake.config;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.repository.DoctorRepository;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.service.TumorBoardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Initializes demo data for testing.
 * Creates 8 doctors (one per domain) and sample patients.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final TumorBoardService tumorBoardService;

    @Bean
    @Profile("!production")
    public CommandLineRunner initDemoData() {
        return args -> {
            log.info("Initializing demo data...");

            // Create 8 doctors (one per domain)
            createDoctorIfNotExists("medical", "demo123", "Dr. Arun Sharma", PhysicianDomain.MEDICAL_ONCOLOGY);
            createDoctorIfNotExists("surgical", "demo123", "Dr. Priya Nair", PhysicianDomain.SURGICAL_ONCOLOGY);
            createDoctorIfNotExists("radiation", "demo123", "Dr. Vikram Patel", PhysicianDomain.RADIATION_ONCOLOGY);
            createDoctorIfNotExists("precision", "demo123", "Dr. Sanjay Gupta", PhysicianDomain.PRECISION_ONCOLOGY);
            createDoctorIfNotExists("palliative", "demo123", "Dr. Meera Krishnan", PhysicianDomain.PALLIATIVE_CARE);
            createDoctorIfNotExists("ayurveda", "demo123", "Dr. Ravi Varma", PhysicianDomain.AYURVEDA_INTEGRATIVE);
            createDoctorIfNotExists("functional", "demo123", "Dr. Anita Desai", PhysicianDomain.FUNCTIONAL_MEDICINE);
            createDoctorIfNotExists("dietician", "demo123", "Dr. Sunita Rao", PhysicianDomain.DIETICIAN_NUTRITION);

            // Create admin user
            createDoctorIfNotExists("admin", "admin123", "System Admin", PhysicianDomain.ADMIN);

            // Create sample referring doctor
            createReferringDoctorIfNotExists("referrer", "demo123", "Dr. Kavita Reddy", "REF-TEST");

            log.info("Created 8 tumor board doctors + 1 admin + 1 referring doctor");

            // Create sample patients for testing
            createSamplePatients();

            log.info("Demo data initialization complete!");
        };
    }

    private void createDoctorIfNotExists(String username, String password, String fullName, PhysicianDomain domain) {
        if (!doctorRepository.existsByUsername(username)) {
            Doctor doctor = Doctor.builder()
                    .username(username)
                    .password(password) // In production, use BCrypt!
                    .fullName(fullName)
                    .domain(domain)
                    .email(username + "@cancerqr.in")
                    .active(true)
                    .build();
            doctorRepository.save(doctor);
            log.debug("Created doctor: {} ({})", fullName, domain);
        }
    }

    private void createReferringDoctorIfNotExists(String username, String password, String fullName, String referralCode) {
        if (!doctorRepository.existsByUsername(username)) {
            Doctor doctor = Doctor.builder()
                    .username(username)
                    .password(password)
                    .fullName(fullName)
                    .domain(PhysicianDomain.REFERRING_DOCTOR)
                    .email(username + "@cancerqr.in")
                    .referralCode(referralCode)
                    .active(true)
                    .build();
            doctorRepository.save(doctor);
            log.debug("Created referring doctor: {} (code: {})", fullName, referralCode);
        }
    }

    private void createSamplePatients() {
        // Only create if no patients exist
        if (patientRepository.count() > 0) {
            log.info("Patients already exist, skipping sample data");
            return;
        }

        // Sample Patient 1: Breast Cancer
        Patient patient1 = Patient.builder()
                .whatsappNumber("+919876543210")
                .name("Rajesh Kumar")
                .age(52)
                .weightKg(new BigDecimal("72.5"))
                .painScale(6)
                .diagnosisDate(LocalDate.now().minusDays(45))
                .cancerType("Breast Cancer")
                .consentGiven(true)
                .conversationState(Patient.ConversationState.COMPLETED)
                .petScanUploaded(true)
                .bloodReportUploaded(true)
                .build();
        patient1 = patientRepository.save(patient1);
        tumorBoardService.createReviewTasksForPatient(patient1.getId());

        // Sample Patient 2: Lung Cancer
        Patient patient2 = Patient.builder()
                .whatsappNumber("+919876543211")
                .name("Priya Menon")
                .age(48)
                .weightKg(new BigDecimal("58.0"))
                .painScale(8)
                .diagnosisDate(LocalDate.now().minusDays(20))
                .cancerType("Lung Cancer")
                .consentGiven(true)
                .conversationState(Patient.ConversationState.COMPLETED)
                .petScanUploaded(true)
                .bloodReportUploaded(true)
                .build();
        patient2 = patientRepository.save(patient2);
        tumorBoardService.createReviewTasksForPatient(patient2.getId());

        // Sample Patient 3: Colorectal Cancer
        Patient patient3 = Patient.builder()
                .whatsappNumber("+919876543212")
                .name("Suresh Iyer")
                .age(65)
                .weightKg(new BigDecimal("78.0"))
                .painScale(4)
                .diagnosisDate(LocalDate.now().minusDays(90))
                .cancerType("Colorectal Cancer")
                .consentGiven(true)
                .conversationState(Patient.ConversationState.COMPLETED)
                .petScanUploaded(true)
                .bloodReportUploaded(false)
                .build();
        patient3 = patientRepository.save(patient3);
        tumorBoardService.createReviewTasksForPatient(patient3.getId());

        log.info("Created 3 sample patients with tumor board review tasks");
    }
}
