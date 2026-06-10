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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ReportDataExtractionService reportDataExtractionService;

    /**
     * Generate analysis for a patient (no AI verification step).
     * Synchronous: the whole DB-write path happens inside this transaction.
     */
    @Transactional
    public AnalysisResult generateAnalysis(UUID patientId) {
        log.info("Generating analysis for patient {}", patientId);

        Patient patient = patientIntakeService.getPatient(patientId);
        if (!patient.hasBasicInfo()) {
            throw new FormulaEngineException("Patient missing required information for analysis");
        }

        // Extract clinical signals (cancer stage, ESR, CRP) before building input.
        // If extraction fails the analysis still proceeds with whatever data we have.
        try {
            reportDataExtractionService.extractAndStoreReportData(patientId);
            patient = patientIntakeService.getPatient(patientId);
        } catch (Exception e) {
            log.warn("Report data extraction failed for patient {}, proceeding without: {}",
                    patientId, e.getMessage());
        }

        AnalysisInput input = createAnalysisInput(patient);
        AnalysisResult result = formulaEngine.generateAnalysis(input);
        persistAnalysis(patient, input, result);

        auditService.logSystemAction(patientId, AuditAction.ANALYSIS_GENERATED,
                String.format("Formula version: %s, Urgent review: %s",
                        result.getFormulaVersion(), result.isRequiresUrgentReview()));

        log.info("Analysis generated successfully for patient {}", patientId);
        return result;
    }

    /**
     * Generate analysis and run AI verification on it.
     *
     * Why synchronous: the prior implementation returned {@code Mono<VerifiedAnalysisResult>}
     * with {@code @Transactional} on top. That's a bug — Spring's @Transactional commits
     * when the method returns the Mono (not when the chain completes), so any DB writes
     * inside the reactive chain happened OUTSIDE any transaction. Here the analysis is
     * persisted by {@link #generateAnalysis} (its own short transaction), then we block
     * once on the WebClient call to Anthropic for verification — a fine pattern in a
     * Servlet stack.
     *
     * If verification throws or the WebClient times out we still return the analysis
     * and treat it as approved — the AI step is an advisory layer, not a gate.
     */
    public VerifiedAnalysisResult generateAndVerify(UUID patientId) {
        AnalysisResult analysis = generateAnalysis(patientId);

        Patient patient = patientIntakeService.getPatient(patientId);
        AnalysisInput input = createAnalysisInput(patient);

        AIVerificationService.VerificationResult verification = null;
        boolean approved = true;
        try {
            verification = aiVerificationService.verifyAnalysis(input, analysis).block();
            if (verification != null) {
                approved = verification.isApproved();
            }
        } catch (Exception e) {
            log.warn("AI verification failed for patient {}, approving without it: {}",
                    patientId, e.getMessage());
        }
        return new VerifiedAnalysisResult(analysis, verification, approved);
    }

    private AnalysisInput createAnalysisInput(Patient patient) {
        String cancerType = patient.getCancerType();
        if (cancerType == null || cancerType.isEmpty()) {
            cancerType = "BREAST_CANCER"; // backward compatibility default
        }
        return AnalysisInput.builder()
                .age(patient.getAge())
                .weightKg(patient.getWeightKg())
                .painScale(patient.getPainScale())
                .diagnosisDate(patient.getDiagnosisDate())
                .hasPetScan(Boolean.TRUE.equals(patient.getPetScanUploaded()))
                .hasBloodReport(Boolean.TRUE.equals(patient.getBloodReportUploaded()))
                .cancerType(cancerType)
                .effectivePainScale(patient.getEffectivePainScale())
                .cancerStage(patient.getCancerStage())
                .esrValue(patient.getEsrValue())
                .crpValue(patient.getCrpValue())
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
     * Patient-safe slice of an analysis: diet + lifestyle guidance only.
     *
     * <p>This record (and the builder below) is the SINGLE source of truth for
     * "what a patient may see before clinician review". The WhatsApp formatter
     * and the patient portal both consume it — change the exclusion policy here,
     * never in a template.
     */
    public record PatientDietGuidance(
            List<String> diets,
            List<String> lifestyles,
            boolean requiresUrgentReview,
            String disclaimer
    ) {}

    /**
     * Extract the patient-safe diet/lifestyle guidance from an analysis result.
     *
     * <p>Fasting is intentionally omitted — it is a medical intervention with
     * contraindications (underweight, cachexia, severe pain). Medicines / doses /
     * CBD / herbs / mushrooms / supportive care are excluded entirely: the system
     * must never deliver treatment suggestions to a patient before a qualified
     * doctor reviews them.
     */
    public PatientDietGuidance buildPatientDietGuidance(AnalysisResult result) {
        LinkedHashSet<String> diets = new LinkedHashSet<>();
        LinkedHashSet<String> lifestyles = new LinkedHashSet<>();
        if (result.getPhysicianProtocols() != null) {
            for (var p : result.getPhysicianProtocols()) {
                if (p.getDiet() != null && !p.getDiet().isBlank()) {
                    diets.add(p.getDiet().trim());
                }
                if (p.getLifestyle() != null && !p.getLifestyle().isBlank()) {
                    lifestyles.add(p.getLifestyle().trim());
                }
            }
        }
        return new PatientDietGuidance(
                new ArrayList<>(diets),
                new ArrayList<>(lifestyles),
                result.isRequiresUrgentReview(),
                result.getDisclaimerText());
    }

    /**
     * Patient-safe guidance for the portal, recomputed from the patient's current
     * data via the (deterministic) formula engine. Present only once an analysis
     * has actually been generated — the portal must not show guidance for an
     * unfinished intake. Nothing is persisted by this call.
     */
    public Optional<PatientDietGuidance> getPatientDietGuidance(UUID patientId) {
        if (analysisRepository.findFirstByPatientIdOrderByCreatedAtDesc(patientId).isEmpty()) {
            return Optional.empty();
        }
        Patient patient = patientIntakeService.getPatient(patientId);
        if (!patient.hasBasicInfo()) {
            return Optional.empty();
        }
        AnalysisResult result = formulaEngine.generateAnalysis(createAnalysisInput(patient));
        return Optional.of(buildPatientDietGuidance(result));
    }

    /**
     * Patient-facing message sent after intake completes.
     *
     * <p>Deliberately contains ONLY general diet + lifestyle guidance plus a note
     * that the full assessment is under clinician review. Medicine / dose / CBD /
     * herb / mushroom / fasting / supportive-care content is intentionally
     * EXCLUDED so the system never delivers treatment suggestions to a patient
     * before a qualified doctor on the tumor board has reviewed them (avoids
     * unlicensed medical advice). The complete analysis — including medicines — is
     * still persisted by {@link #generateAnalysis} and shown to clinicians on the
     * dashboard; this method only changes what the PATIENT sees.
     */
    public String formatPatientDietMessage(AnalysisResult result) {
        PatientDietGuidance guidance = buildPatientDietGuidance(result);

        StringBuilder msg = new StringBuilder();

        msg.append("🥗 *YOUR DIET & LIFESTYLE GUIDANCE*\n");
        msg.append("━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        msg.append("Thank you for completing your intake. Below are some general "
                + "diet and lifestyle suggestions you can begin with.\n\n");

        if (!guidance.diets().isEmpty()) {
            msg.append("🍽️ *Dietary focus:*\n");
            for (String d : guidance.diets()) {
                msg.append("• ").append(d).append("\n");
            }
            msg.append("\n");
        }
        if (!guidance.lifestyles().isEmpty()) {
            msg.append("🌿 *Lifestyle:*\n");
            for (String l : guidance.lifestyles()) {
                msg.append("• ").append(l).append("\n");
            }
            msg.append("\n");
        }

        // Safe, generic baseline guidance — no medical claims, no dosing.
        msg.append("🥦 *General healthy-eating tips:*\n");
        msg.append("• Eat plenty of vegetables and fruit\n");
        msg.append("• Choose whole, minimally processed foods\n");
        msg.append("• Stay well hydrated with water\n");
        msg.append("• Limit added sugar and processed/red meat\n\n");

        // Safety signal (directs to care; not a treatment suggestion).
        if (result.isRequiresUrgentReview()) {
            msg.append("⚠️ Based on your responses, please seek prompt medical "
                    + "attention if your symptoms are severe or worsening.\n\n");
        }

        msg.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        msg.append("🩺 *NEXT STEPS*\n");
        msg.append("Your information has been securely shared with our medical "
                + "team. A qualified doctor will review your reports and follow up "
                + "with you regarding any treatment.\n\n");
        msg.append("⚠️ This message provides general dietary guidance only. It is "
                + "NOT a prescription or treatment plan. Please do not start, stop, "
                + "or change any medication or treatment without consulting your "
                + "doctor.\n");

        if (result.getDisclaimerText() != null && !result.getDisclaimerText().isBlank()) {
            msg.append("\n").append(result.getDisclaimerText());
        }

        return msg.toString();
    }

    /**
     * Full clinician-oriented analysis format (includes medicine suggestions).
     *
     * <p><strong>Not sent to patients.</strong> The patient WhatsApp flow uses
     * {@link #formatPatientDietMessage} instead; this richer format is retained
     * for clinician/export use where medicine content is appropriate.
     */
    public FormattedAnalysisMessage formatForWhatsApp(AnalysisResult result) {
        StringBuilder fullMessage = new StringBuilder();

        // Header
        String header = "🏥 *INITIAL ASSESSMENT REPORT*\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━━━\n\n";
        fullMessage.append(header);

        // Patient summary (from assessment summary)
        fullMessage.append(result.getAssessmentSummary());

        // Cancer-type-specific protocol summary (consolidated view for patient)
        if (result.getPhysicianProtocols() != null && !result.getPhysicianProtocols().isEmpty()) {
            fullMessage.append("\n🎯 *CANCER-SPECIFIC PROTOCOL HIGHLIGHTS:*\n\n");
            // Show a consolidated view from Medical Oncology domain
            result.getPhysicianProtocols().stream()
                    .filter(p -> "Medical Oncology".equals(p.getPhysicianDomain()))
                    .findFirst()
                    .ifPresent(medOnc -> {
                        if (medOnc.getEcsProducts() != null && !medOnc.getEcsProducts().isEmpty()) {
                            fullMessage.append("• *ECS Products:* " + String.join(", ", medOnc.getEcsProducts()) + "\n");
                        }
                        if (medOnc.getDiet() != null) {
                            fullMessage.append("• *Diet:* " + medOnc.getDiet() + "\n");
                        }
                        if (medOnc.getFasting() != null) {
                            fullMessage.append("• *Fasting:* " + medOnc.getFasting() + "\n");
                        }
                        if (medOnc.getLifestyle() != null) {
                            fullMessage.append("• *Lifestyle:* " + medOnc.getLifestyle() + "\n");
                        }
                    });
            fullMessage.append("\n_Detailed per-specialist protocols are available on the tumor board dashboard._\n\n");
        }
        
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
            
            Map<String, Object> physicianProtocols = new HashMap<>();
            if (result.getPhysicianProtocols() != null) {
                physicianProtocols.put("protocols", result.getPhysicianProtocols());
            }

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
