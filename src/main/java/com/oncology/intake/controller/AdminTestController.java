package com.oncology.intake.controller;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.repository.ReportRepository;
import com.oncology.intake.service.AnalysisService;
import com.oncology.intake.service.StorageService;
import com.oncology.intake.service.TumorBoardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Admin endpoint for testing the full patient intake flow via curl.
 * NOT for production use.
 */
@RestController
@RequestMapping("/admin/test")
@RequiredArgsConstructor
@Slf4j
public class AdminTestController {

    private final PatientRepository patientRepository;
    private final ReportRepository reportRepository;
    private final StorageService storageService;
    private final AnalysisService analysisService;
    private final TumorBoardService tumorBoardService;

    /**
     * Create a test patient with uploaded reports, generate analysis, and create tumor board reviews.
     *
     * curl -X POST http://localhost:8080/admin/test/patient \
     *   -F "name=Sudheer Rai" \
     *   -F "age=62" \
     *   -F "cancerType=LUNG_CANCER" \
     *   -F "weightKg=71" \
     *   -F "painScale=7" \
     *   -F "diagnosisDate=2026-02-22" \
     *   -F "whatsappNumber=+919234560099" \
     *   -F "petScan=@/path/to/pet_scan.pdf" \
     *   -F "bloodReport=@/path/to/blood_report.pdf"
     */
    @PostMapping("/patient")
    public ResponseEntity<Map<String, Object>> createTestPatient(
            @RequestParam String name,
            @RequestParam int age,
            @RequestParam String cancerType,
            @RequestParam BigDecimal weightKg,
            @RequestParam int painScale,
            @RequestParam String diagnosisDate,
            @RequestParam String whatsappNumber,
            @RequestParam("petScan") MultipartFile petScan,
            @RequestParam("bloodReport") MultipartFile bloodReport) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 1. Create patient
            Patient patient = Patient.builder()
                    .whatsappNumber(whatsappNumber)
                    .name(name)
                    .age(age)
                    .cancerType(cancerType)
                    .weightKg(weightKg)
                    .painScale(painScale)
                    .diagnosisDate(LocalDate.parse(diagnosisDate))
                    .conversationState(ConversationState.COMPLETED)
                    .intakeCompleted(true)
                    .consentGiven(true)
                    .consentTimestamp(LocalDateTime.now())
                    .lastInteractionAt(LocalDateTime.now())
                    .isActive(true)
                    .petScanUploaded(false)
                    .bloodReportUploaded(false)
                    .build();

            patient = patientRepository.save(patient);
            UUID patientId = patient.getId();
            log.info("Created test patient: {} ({})", name, patientId);
            result.put("patientId", patientId);
            result.put("name", name);

            // 2. Store PET scan
            StorageService.StorageResult petResult = storageService.storeFile(
                    petScan.getBytes(), petScan.getOriginalFilename(),
                    "application/pdf", patientId);

            Report petReport = Report.builder()
                    .patient(patient)
                    .reportType(ReportType.PET_SCAN)
                    .storageLocation(petResult.storageKey())
                    .fileName(petScan.getOriginalFilename())
                    .originalFileName(petScan.getOriginalFilename())
                    .contentType("application/pdf")
                    .fileSizeBytes(petResult.sizeBytes())
                    .checksum(petResult.checksum())
                    .processed(false)
                    .build();
            reportRepository.save(petReport);
            patient.setPetScanUploaded(true);
            log.info("Stored PET scan report: {}", petResult.storageKey());
            result.put("petScanStorageKey", petResult.storageKey());

            // 3. Store blood report
            StorageService.StorageResult bloodResult = storageService.storeFile(
                    bloodReport.getBytes(), bloodReport.getOriginalFilename(),
                    "application/pdf", patientId);

            Report bloodRpt = Report.builder()
                    .patient(patient)
                    .reportType(ReportType.BLOOD_REPORT)
                    .storageLocation(bloodResult.storageKey())
                    .fileName(bloodReport.getOriginalFilename())
                    .originalFileName(bloodReport.getOriginalFilename())
                    .contentType("application/pdf")
                    .fileSizeBytes(bloodResult.sizeBytes())
                    .checksum(bloodResult.checksum())
                    .processed(false)
                    .build();
            reportRepository.save(bloodRpt);
            patient.setBloodReportUploaded(true);
            patientRepository.save(patient);
            log.info("Stored blood report: {}", bloodResult.storageKey());
            result.put("bloodReportStorageKey", bloodResult.storageKey());

            // 4. Generate analysis with AI verification (triggers report data extraction internally)
            try {
                var verifiedResult = analysisService.generateAndVerifyAnalysis(patientId).block();
                result.put("analysisGenerated", true);
                result.put("assessmentSummary", verifiedResult.analysisResult().getAssessmentSummary());
                result.put("urgentReview", verifiedResult.analysisResult().isRequiresUrgentReview());
                result.put("aiVerified", verifiedResult.verification() != null);
                result.put("aiApproved", verifiedResult.approvedToSend());
                if (verifiedResult.verification() != null) {
                    result.put("verificationConfidence", verifiedResult.verification().getConfidenceScore());
                    result.put("verificationSuggestions", verifiedResult.verification().getSuggestions());
                    result.put("verificationIssues", verifiedResult.verification().getIssues());
                }
                log.info("Analysis generated and AI-verified for patient: {}", patientId);
            } catch (Exception e) {
                result.put("analysisGenerated", false);
                result.put("analysisError", e.getMessage());
                log.warn("Analysis generation failed: {}", e.getMessage());
            }

            // Re-fetch patient to get extracted values
            patient = patientRepository.findById(patientId).orElse(patient);
            result.put("cancerStage", patient.getCancerStage());
            result.put("esrValue", patient.getEsrValue());
            result.put("crpValue", patient.getCrpValue());
            result.put("effectivePainScale", patient.getEffectivePainScale());

            // 5. Create tumor board review tasks
            try {
                tumorBoardService.createReviewTasksForPatient(patientId);
                result.put("tumorBoardReviewsCreated", true);
                log.info("Tumor board reviews created for patient: {}", patientId);
            } catch (Exception e) {
                result.put("tumorBoardReviewsCreated", false);
                result.put("tumorBoardError", e.getMessage());
                log.warn("Tumor board creation failed: {}", e.getMessage());
            }

            result.put("status", "SUCCESS");
            result.put("dashboardUrl", "/dashboard/patient/" + patientId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to create test patient: {}", e.getMessage(), e);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
