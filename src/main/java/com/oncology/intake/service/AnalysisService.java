package com.oncology.intake.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncology.intake.dto.AnalysisDto.*;
import com.oncology.intake.engine.FormulaEngine;
import com.oncology.intake.entity.Analysis;
import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.exception.IntakeExceptions.AnalysisNotFoundException;
import com.oncology.intake.exception.IntakeExceptions.FormulaEngineException;
import com.oncology.intake.repository.AnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Service for generating and managing patient analyses.
 * Coordinates between formula engine, AI verification, and persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    private final FormulaEngine formulaEngine;
    private final AnalysisRepository analysisRepository;
    private final PatientIntakeService patientIntakeService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AIVerificationService aiVerificationService;

    /**
     * Generate analysis for a patient (without verification)
     */
    @Transactional
    public AnalysisResult generateAnalysis(UUID patientId) {
        return generateAnalysisInternal(patientId, false).block();
    }

    /**
     * Generate analysis with AI verification before sending
     */
    @Transactional
    public Mono<VerifiedAnalysisResult> generateAndVerifyAnalysis(UUID patientId) {
        return generateAnalysisInternal(patientId, true)
                .map(result -> {
                    AnalysisInput input = createAnalysisInput(patientIntakeService.getPatient(patientId));
                    return new VerifiedAnalysisResult(result, null, true); // Placeholder
                })
                .flatMap(result -> {
                    AnalysisInput input = createAnalysisInput(patientIntakeService.getPatient(patientId));
                    return aiVerificationService.verifyAnalysis(input, result.analysisResult())
                            .map(verification -> new VerifiedAnalysisResult(
                                    result.analysisResult(),
                                    verification,
                                    verification.isApproved()
                            ));
                });
    }

    /**
     * Internal method to generate analysis
     */
    private Mono<AnalysisResult> generateAnalysisInternal(UUID patientId, boolean withVerification) {
        log.info("Generating analysis for patient: {} (verification: {})", patientId, withVerification);
        
        Patient patient = patientIntakeService.getPatient(patientId);
        
        // Validate patient has all required info
        if (!patient.hasBasicInfo()) {
            throw new FormulaEngineException("Patient missing required information for analysis");
        }
        
        // Create analysis input
        AnalysisInput input = createAnalysisInput(patient);
        
        // Generate analysis using formula engine
        AnalysisResult result = formulaEngine.generateAnalysis(input);
        
        // Persist analysis
        Analysis analysis = persistAnalysis(patient, input, result);
        
        auditService.logSystemAction(patientId, AuditAction.ANALYSIS_GENERATED,
                String.format("Formula version: %s, Urgent review: %s",
                        result.getFormulaVersion(), result.isRequiresUrgentReview()));
        
        log.info("Analysis generated successfully for patient: {}", patientId);
        return Mono.just(result);
    }

    private AnalysisInput createAnalysisInput(Patient patient) {
        return AnalysisInput.builder()
                .age(patient.getAge())
                .weightKg(patient.getWeightKg())
                .painScale(patient.getPainScale())
                .diagnosisDate(patient.getDiagnosisDate())
                .hasPetScan(Boolean.TRUE.equals(patient.getPetScanUploaded()))
                .hasBloodReport(Boolean.TRUE.equals(patient.getBloodReportUploaded()))
                .build();
    }

    /**
     * Result containing analysis and verification status
     */
    public record VerifiedAnalysisResult(
            AnalysisResult analysisResult,
            AIVerificationService.VerificationResult verification,
            boolean approvedToSend
    ) {}

    /**
     * Get latest analysis for a patient
     */
    public Optional<Analysis> getLatestAnalysis(UUID patientId) {
        return analysisRepository.findFirstByPatientIdOrderByCreatedAtDesc(patientId);
    }

    /**
     * Get analysis by ID
     */
    public Analysis getAnalysis(UUID analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new AnalysisNotFoundException(analysisId.toString()));
    }

    /**
     * Mark analysis as sent to patient
     */
    @Transactional
    public void markAsSent(UUID analysisId) {
        Analysis analysis = getAnalysis(analysisId);
        analysis.setSentToPatient(true);
        analysis.setSentAt(LocalDateTime.now());
        analysisRepository.save(analysis);
        
        auditService.logSystemAction(analysis.getPatient().getId(), 
                AuditAction.ANALYSIS_SENT, "Analysis sent to patient via WhatsApp");
    }

    /**
     * Format analysis for WhatsApp message
     */
    public FormattedAnalysisMessage formatForWhatsApp(AnalysisResult result) {
        StringBuilder fullMessage = new StringBuilder();
        
        // Header
        String header = "🏥 *INITIAL ASSESSMENT REPORT*\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━━━\n\n";
        fullMessage.append(header);
        
        // Patient summary (from assessment summary)
        fullMessage.append(result.getAssessmentSummary());
        
        // Medicine recommendations
        StringBuilder medicineSection = new StringBuilder();
        if (!result.getRecommendedMedicines().isEmpty()) {
            medicineSection.append("💊 *INITIAL MEDICINE SUGGESTIONS:*\n\n");
            
            int count = 1;
            for (MedicineRecommendation med : result.getRecommendedMedicines()) {
                medicineSection.append(String.format("%d. *%s* (%s)\n", 
                        count++, med.getName(), med.getCategory()));
                medicineSection.append(String.format("   • Dose: %s\n", med.getDose()));
                medicineSection.append(String.format("   • Frequency: %s\n", med.getFrequency()));
                medicineSection.append(String.format("   • Duration: %d days\n", med.getDurationDays()));
                if (med.getNotes() != null) {
                    medicineSection.append(String.format("   • Note: %s\n", med.getNotes()));
                }
                if (med.isRequiresSpecialistReview()) {
                    medicineSection.append("   ⚠️ _Requires specialist review_\n");
                }
                medicineSection.append("\n");
            }
        }
        fullMessage.append(medicineSection);
        
        // Supportive care
        StringBuilder supportiveSection = new StringBuilder();
        if (!result.getSupportiveCare().isEmpty()) {
            supportiveSection.append("🩺 *SUPPORTIVE CARE:*\n\n");
            
            for (SupportiveCare care : result.getSupportiveCare()) {
                supportiveSection.append(String.format("• *%s* (%s)", 
                        care.getName(), care.getCategory()));
                if (care.getDose() != null) {
                    supportiveSection.append(String.format(" - %s", care.getDose()));
                }
                if (care.getFrequency() != null) {
                    supportiveSection.append(String.format(", %s", care.getFrequency()));
                }
                supportiveSection.append("\n");
                if (care.getNotes() != null) {
                    supportiveSection.append(String.format("  _%s_\n", care.getNotes()));
                }
            }
            supportiveSection.append("\n");
        }
        fullMessage.append(supportiveSection);
        
        // Alerts section
        StringBuilder alertsSection = new StringBuilder();
        var urgentAlerts = result.getAlerts().stream()
                .filter(a -> a.getSeverity() == AlertSeverity.URGENT)
                .collect(Collectors.toList());
        
        if (!urgentAlerts.isEmpty()) {
            alertsSection.append("🚨 *URGENT ATTENTION REQUIRED:*\n\n");
            for (Alert alert : urgentAlerts) {
                alertsSection.append(String.format("⚠️ %s\n", alert.getMessage()));
                alertsSection.append(String.format("   → %s\n\n", alert.getRecommendation()));
            }
        }
        fullMessage.append(alertsSection);
        
        // Disclaimer
        String disclaimer = "\n━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                           "⚠️ *IMPORTANT DISCLAIMER:*\n\n" +
                           result.getDisclaimerText() + "\n" +
                           "━━━━━━━━━━━━━━━━━━━━━━━━";
        fullMessage.append(disclaimer);
        
        return FormattedAnalysisMessage.builder()
                .header(header)
                .patientSummary(result.getAssessmentSummary())
                .medicineSection(medicineSection.toString())
                .supportiveCareSection(supportiveSection.toString())
                .alertsSection(alertsSection.toString())
                .disclaimer(disclaimer)
                .fullMessage(fullMessage.toString())
                .build();
    }

    /**
     * Generate a short WhatsApp message (for message length limits)
     */
    public String formatShortMessage(AnalysisResult result) {
        StringBuilder msg = new StringBuilder();
        
        msg.append("🏥 *Initial Assessment Complete*\n\n");
        msg.append(String.format("Pain Level: %s\n", result.getDerivedMetrics().getPainCategory()));
        msg.append(String.format("Medicines Suggested: %d\n\n", 
                result.getRecommendedMedicines().size()));
        
        // List medicine names only
        msg.append("*Suggested Medicines:*\n");
        for (MedicineRecommendation med : result.getRecommendedMedicines()) {
            msg.append(String.format("• %s (%s)\n", med.getName(), med.getDose()));
        }
        
        msg.append("\n⚠️ _This is an initial suggestion only. ");
        msg.append("Please consult your oncologist before taking any medication._");
        
        return msg.toString();
    }

    // =============== Private Methods ===============

    private Analysis persistAnalysis(Patient patient, AnalysisInput input, AnalysisResult result) {
        try {
            // Convert objects to maps for JSON storage
            Map<String, Object> derivedMetrics = objectMapper.convertValue(
                    result.getDerivedMetrics(), 
                    new TypeReference<Map<String, Object>>() {});
            
            Map<String, Object> medicines = new HashMap<>();
            medicines.put("recommendations", result.getRecommendedMedicines());
            
            Map<String, Object> supportiveCare = new HashMap<>();
            supportiveCare.put("items", result.getSupportiveCare());
            
            Map<String, Object> alerts = new HashMap<>();
            alerts.put("items", result.getAlerts());
            
            Map<String, Object> inputSnapshot = objectMapper.convertValue(
                    input, 
                    new TypeReference<Map<String, Object>>() {});
            
            Analysis analysis = Analysis.builder()
                    .patient(patient)
                    .formulaVersion(result.getFormulaVersion())
                    .derivedMetricsJson(derivedMetrics)
                    .recommendedMedicinesJson(medicines)
                    .supportiveCareJson(supportiveCare)
                    .alertsJson(alerts)
                    .assessmentSummary(result.getAssessmentSummary())
                    .disclaimerText(result.getDisclaimerText())
                    .requiresUrgentReview(result.isRequiresUrgentReview())
                    .inputSnapshotJson(inputSnapshot)
                    .build();
            
            return analysisRepository.save(analysis);
            
        } catch (Exception e) {
            log.error("Failed to persist analysis", e);
            throw new FormulaEngineException("Failed to save analysis results", e);
        }
    }
}
