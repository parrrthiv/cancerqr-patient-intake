package com.oncology.intake.service;

import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.exception.IntakeExceptions.PatientNotFoundException;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.repository.ReportRepository;
import com.oncology.intake.security.WhatsAppNumberHasher;
import com.oncology.intake.util.MediaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing patient intake data.
 * Handles patient creation, updates, and state management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientIntakeService {

    private final PatientRepository patientRepository;
    private final ReportRepository reportRepository;
    private final StorageService storageService;
    private final AuditService auditService;
    private final WhatsAppNumberHasher whatsAppNumberHasher;
    private final ReportDataExtractionAsyncRunner reportExtractionRunner;

    /** Hard cap on uploaded medical reports, in megabytes. Wired from {@code app.max-upload-size-mb}. */
    @Value("${app.max-upload-size-mb:25}")
    private long maxUploadSizeMb;

    /**
     * Find or create a patient by WhatsApp number
     */
    @Transactional
    public Patient findOrCreatePatient(String whatsappNumber) {
        return findOrCreatePatient(whatsappNumber, null);
    }

    @Transactional
    public Patient findOrCreatePatient(String whatsappNumber, String contactName) {
        // Normalise so the same human always produces the same hash regardless
        // of how the number is formatted on the way in. Store the normalised
        // form too, so encrypted column and hash column never drift apart.
        String normalised = WhatsAppNumberHasher.normalise(whatsappNumber);
        String hash = whatsAppNumberHasher.hash(normalised);

        return patientRepository.findByWhatsappNumberHash(hash)
                .map(patient -> {
                    patient.setLastInteractionAt(LocalDateTime.now());
                    if (patient.getName() == null && contactName != null) {
                        patient.setName(contactName);
                    }
                    return patientRepository.save(patient);
                })
                .orElseGet(() -> {
                    Patient newPatient = Patient.builder()
                            .whatsappNumber(normalised)
                            // whatsappNumberHash is set automatically by
                            // PatientHashListener on @PrePersist.
                            .name(contactName)
                            .conversationState(ConversationState.INITIAL)
                            .lastInteractionAt(LocalDateTime.now())
                            .build();
                    Patient saved = patientRepository.save(newPatient);
                    auditService.logSystemAction(saved.getId(), AuditAction.PATIENT_CREATED,
                            "Patient created from WhatsApp");
                    log.info("Created new patient record for WhatsApp interaction");
                    return saved;
                });
    }

    /**
     * Get patient by ID
     */
    public Patient getPatient(UUID patientId) {
        return patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId.toString()));
    }

    /**
     * Get patient by WhatsApp number. Hashes internally — callers pass plaintext.
     */
    public Optional<Patient> findByWhatsappNumber(String whatsappNumber) {
        return patientRepository.findByWhatsappNumberHash(
                whatsAppNumberHasher.hash(whatsappNumber));
    }

    /**
     * Update patient cancer type
     */
    @Transactional
    public Patient updateCancerType(UUID patientId, String cancerType) {
        Patient patient = getPatient(patientId);
        patient.setCancerType(cancerType);
        patient.setLastInteractionAt(LocalDateTime.now());

        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED, "Cancer type updated: " + cancerType);
        return patientRepository.save(patient);
    }

    /**
     * Update patient age
     */
    @Transactional
    public Patient updateAge(UUID patientId, Integer age) {
        Patient patient = getPatient(patientId);
        patient.setAge(age);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED, "Age updated");
        return patientRepository.save(patient);
    }

    /**
     * Update patient weight
     */
    @Transactional
    public Patient updateWeight(UUID patientId, BigDecimal weightKg) {
        Patient patient = getPatient(patientId);
        patient.setWeightKg(weightKg);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED, "Weight updated");
        return patientRepository.save(patient);
    }

    /**
     * Update patient pain scale
     */
    @Transactional
    public Patient updatePainScale(UUID patientId, Integer painScale) {
        Patient patient = getPatient(patientId);
        patient.setPainScale(painScale);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED, "Pain scale updated");
        return patientRepository.save(patient);
    }

    /**
     * Update patient diagnosis date
     */
    @Transactional
    public Patient updateDiagnosisDate(UUID patientId, LocalDate diagnosisDate) {
        Patient patient = getPatient(patientId);
        patient.setDiagnosisDate(diagnosisDate);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED, "Diagnosis date updated");
        return patientRepository.save(patient);
    }

    /**
     * Update conversation state
     */
    @Transactional
    public Patient updateConversationState(UUID patientId, ConversationState newState) {
        Patient patient = getPatient(patientId);
        ConversationState oldState = patient.getConversationState();
        patient.setConversationState(newState);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.CONVERSATION_STATE_CHANGED,
                String.format("State: %s -> %s", oldState, newState));
        
        log.debug("Patient {} state changed: {} -> {}", patientId, oldState, newState);
        return patientRepository.save(patient);
    }

    /**
     * Atomically claim an intake step: advance the conversation state to
     * {@code next} only if the patient is still in {@code expected}. Returns
     * true if this caller won the transition. Lets concurrent or re-delivered
     * WhatsApp media uploads avoid being ingested twice against the same step
     * (see {@code ConversationService.processMediaMessage}).
     */
    @Transactional
    public boolean advanceStateIfCurrent(UUID patientId, ConversationState expected,
                                         ConversationState next) {
        int updated = patientRepository.advanceConversationStateIfCurrent(patientId, expected, next);
        if (updated > 0) {
            auditService.logPatientAction(patientId, AuditAction.CONVERSATION_STATE_CHANGED,
                    String.format("State: %s -> %s (claimed)", expected, next));
        }
        return updated > 0;
    }

    /**
     * Record consent given
     */
    @Transactional
    public Patient recordConsent(UUID patientId) {
        Patient patient = getPatient(patientId);
        patient.setConsentGiven(true);
        patient.setConsentTimestamp(LocalDateTime.now());
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.CONSENT_GIVEN, "Patient consent recorded");
        return patientRepository.save(patient);
    }

    /**
     * Store a medical report
     */
    @Transactional
    public Report storeReport(UUID patientId, ReportType reportType,
                               Path file, String fileName,
                               String contentType, String whatsappMediaId) {
        Patient patient = getPatient(patientId);

        // Validate BEFORE storing: rejects empty / oversized / disallowed MIME /
        // declared-vs-actual content type mismatch. WhatsApp forwards whatever
        // content type the sender claimed; never trust it without this check.
        // File-based validation reads only the size + first bytes (no heap buffer).
        MediaValidator.validate(file, contentType, maxUploadSizeMb * 1024L * 1024L);

        // Store file (streamed off disk; no full-file heap buffer)
        StorageService.StorageResult storageResult =
                storageService.storeFile(file, fileName, contentType, patientId);
        
        // Create report record
        Report report = Report.builder()
                .patient(patient)
                .reportType(reportType)
                .storageLocation(storageResult.storageKey())
                .fileName(generateFileName(reportType, fileName))
                .originalFileName(fileName)
                .contentType(contentType)
                .fileSizeBytes(storageResult.sizeBytes())
                .checksum(storageResult.checksum())
                .whatsappMediaId(whatsappMediaId)
                .build();
        
        Report savedReport = reportRepository.save(report);
        
        // Update patient upload flags
        if (reportType == ReportType.PET_SCAN) {
            patient.setPetScanUploaded(true);
        } else if (reportType == ReportType.BLOOD_REPORT) {
            patient.setBloodReportUploaded(true);
        }
        patient.setLastInteractionAt(LocalDateTime.now());
        patientRepository.save(patient);
        
        auditService.logPatientAction(patientId, AuditAction.REPORT_UPLOADED,
                String.format("Report type: %s, size: %d bytes", reportType, storageResult.sizeBytes()));

        log.info("Stored {} report for patient {}", reportType, patientId);

        // Kick off async extraction so the request thread isn't blocked by
        // PDFBox parsing. If extraction fails, the dashboard view will
        // retrigger it lazily; see DashboardController.viewPatient.
        reportExtractionRunner.runForPatient(patientId);

        return savedReport;
    }

    /**
     * Link a referring doctor to a patient
     */
    @Transactional
    public Patient linkReferringDoctor(UUID patientId, Doctor doctor) {
        Patient patient = getPatient(patientId);
        patient.setReferringDoctor(doctor);
        patient.setLastInteractionAt(LocalDateTime.now());

        auditService.logPatientAction(patientId, AuditAction.PATIENT_UPDATED,
                "Linked to referring doctor: " + doctor.getFullName());
        return patientRepository.save(patient);
    }

    /**
     * Mark intake as completed
     */
    @Transactional
    public Patient markIntakeCompleted(UUID patientId) {
        Patient patient = getPatient(patientId);
        patient.setIntakeCompleted(true);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logPatientAction(patientId, AuditAction.INTAKE_COMPLETED, "Intake completed");
        return patientRepository.save(patient);
    }

    /**
     * Check if patient has all required uploads
     */
    public boolean hasAllRequiredUploads(UUID patientId) {
        Patient patient = getPatient(patientId);
        return patient.hasAllUploads();
    }

    /**
     * Check if patient has all basic information
     */
    public boolean hasAllBasicInfo(UUID patientId) {
        Patient patient = getPatient(patientId);
        return patient.hasBasicInfo();
    }

    /**
     * Reset patient for retry (e.g., after conversation timeout)
     */
    @Transactional
    public Patient resetPatientIntake(UUID patientId) {
        Patient patient = getPatient(patientId);
        patient.setConversationState(ConversationState.INITIAL);
        patient.setIntakeCompleted(false);
        patient.setLastInteractionAt(LocalDateTime.now());
        
        auditService.logSystemAction(patientId, AuditAction.PATIENT_UPDATED, "Patient intake reset");
        return patientRepository.save(patient);
    }

    /**
     * Anonymize patient data (for GDPR compliance)
     */
    @Transactional
    public void anonymizePatient(UUID patientId) {
        // Delete stored reports first
        var storageKeys = reportRepository.findStorageLocationsByPatientId(patientId);
        for (String key : storageKeys) {
            try {
                storageService.deleteFile(key);
            } catch (Exception e) {
                log.warn("Failed to delete stored file: {}", key);
            }
        }
        
        // Anonymize patient record
        patientRepository.anonymizePatient(patientId);
        
        auditService.logSystemAction(patientId, AuditAction.PATIENT_ANONYMIZED, 
                "Patient data anonymized");
        log.info("Patient {} anonymized", patientId);
    }

    // =============== Private Methods ===============

    private String generateFileName(ReportType reportType, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return String.format("%s_%s%s", 
                reportType.name().toLowerCase(), 
                UUID.randomUUID().toString().substring(0, 8),
                extension);
    }
}
