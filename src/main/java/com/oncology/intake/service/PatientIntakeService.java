package com.oncology.intake.service;

import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.exception.IntakeExceptions.PatientNotFoundException;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * Find or create a patient by WhatsApp number
     */
    @Transactional
    public Patient findOrCreatePatient(String whatsappNumber) {
        return findOrCreatePatient(whatsappNumber, null);
    }

    @Transactional
    public Patient findOrCreatePatient(String whatsappNumber, String contactName) {
        return patientRepository.findByWhatsappNumber(whatsappNumber)
                .map(patient -> {
                    patient.setLastInteractionAt(LocalDateTime.now());
                    if (patient.getName() == null && contactName != null) {
                        patient.setName(contactName);
                    }
                    return patientRepository.save(patient);
                })
                .orElseGet(() -> {
                    Patient newPatient = Patient.builder()
                            .whatsappNumber(whatsappNumber)
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
     * Get patient by WhatsApp number
     */
    public Optional<Patient> findByWhatsappNumber(String whatsappNumber) {
        return patientRepository.findByWhatsappNumber(whatsappNumber);
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
                               byte[] content, String fileName, 
                               String contentType, String whatsappMediaId) {
        Patient patient = getPatient(patientId);
        
        // Store file in storage service
        StorageService.StorageResult storageResult = 
                storageService.storeFile(content, fileName, contentType, patientId);
        
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
                String.format("Report type: %s, size: %d bytes", reportType, content.length));
        
        log.info("Stored {} report for patient {}", reportType, patientId);
        return savedReport;
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
