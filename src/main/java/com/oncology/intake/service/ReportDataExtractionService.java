package com.oncology.intake.service;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for extracting clinical data from uploaded PDF reports.
 * Extracts cancer stage from PET scans, ESR/CRP from blood reports,
 * and adjusts pain scale based on inflammation markers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDataExtractionService {

    private final ReportRepository reportRepository;
    private final PatientRepository patientRepository;
    private final StorageService storageService;

    private static final Pattern STAGE_PATTERN = Pattern.compile(
            "Stage\\s+(I{1,3}V?|IV)[A-C]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESR_PATTERN = Pattern.compile(
            "ESR[:\\s]+(\\d+\\.?\\d*)\\s*mm/hr", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRP_PATTERN = Pattern.compile(
            "CRP\\)?[:\\s]+(\\d+\\.?\\d*)\\s*mg/[Ll]", Pattern.CASE_INSENSITIVE);

    /**
     * Extract data from uploaded reports and update the patient entity.
     * Extracts cancer stage from PET scan and ESR/CRP from blood report.
     * Calculates effective pain scale based on inflammation markers.
     */
    @Transactional
    public void extractAndStoreReportData(UUID patientId) {
        log.info("Extracting report data for patient: {}", patientId);

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found: " + patientId));

        // Extract cancer stage from PET scan
        List<Report> petScans = reportRepository.findByPatientIdAndReportType(patientId, ReportType.PET_SCAN);
        for (Report report : petScans) {
            try {
                byte[] fileData = storageService.retrieveFile(report.getStorageLocation());
                String text = extractTextFromPdf(fileData);
                String stage = extractCancerStage(text);
                if (stage != null) {
                    patient.setCancerStage(stage);
                    log.info("Extracted cancer stage '{}' for patient: {}", stage, patientId);
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to extract data from PET scan report {}: {}", report.getId(), e.getMessage());
            }
        }

        // Extract ESR and CRP from blood report
        List<Report> bloodReports = reportRepository.findByPatientIdAndReportType(patientId, ReportType.BLOOD_REPORT);
        for (Report report : bloodReports) {
            try {
                byte[] fileData = storageService.retrieveFile(report.getStorageLocation());
                String text = extractTextFromPdf(fileData);

                BigDecimal esr = extractESR(text);
                BigDecimal crp = extractCRP(text);

                if (esr != null) {
                    patient.setEsrValue(esr);
                    log.info("Extracted ESR {} mm/hr for patient: {}", esr, patientId);
                }
                if (crp != null) {
                    patient.setCrpValue(crp);
                    log.info("Extracted CRP {} mg/L for patient: {}", crp, patientId);
                }

                if (esr != null || crp != null) break;
            } catch (Exception e) {
                log.warn("Failed to extract data from blood report {}: {}", report.getId(), e.getMessage());
            }
        }

        // Calculate effective pain scale
        if (patient.getPainScale() != null) {
            int effectivePain = calculateEffectivePainScale(
                    patient.getPainScale(), patient.getEsrValue(), patient.getCrpValue());
            patient.setEffectivePainScale(effectivePain);

            if (effectivePain > patient.getPainScale()) {
                log.info("Pain scale adjusted from {} to {} based on inflammation markers for patient: {}",
                        patient.getPainScale(), effectivePain, patientId);
            }
        }

        patientRepository.save(patient);
        log.info("Report data extraction completed for patient: {}", patientId);
    }

    /**
     * Extract text content from a PDF file.
     */
    String extractTextFromPdf(byte[] pdfData) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            log.error("Failed to extract text from PDF: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Extract cancer stage from text using regex.
     * Matches patterns like "Stage IV", "Stage IIIB", "Stage II", etc.
     */
    String extractCancerStage(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = STAGE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(0);
        }
        return null;
    }

    /**
     * Extract ESR value from text.
     * Matches patterns like "ESR: 48 mm/hr", "ESR 48.5 mm/hr".
     */
    BigDecimal extractESR(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = ESR_PATTERN.matcher(text);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        return null;
    }

    /**
     * Extract CRP value from text.
     * Matches patterns like "CRP: 22 mg/L", "CRP 22.5 mg/l".
     */
    BigDecimal extractCRP(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = CRP_PATTERN.matcher(text);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        return null;
    }

    /**
     * Calculate effective pain scale based on inflammation markers.
     * Only adjusts upward - never reduces self-reported pain.
     *
     * ESR > 40 mm/hr OR CRP > 20 mg/L -> minimum effective pain = 7 (HIGH)
     * ESR > 20 mm/hr OR CRP > 10 mg/L -> minimum effective pain = 5 (MODERATE)
     */
    int calculateEffectivePainScale(int selfReportedPain, BigDecimal esr, BigDecimal crp) {
        int minimumPain = selfReportedPain;

        boolean highInflammation = (esr != null && esr.compareTo(BigDecimal.valueOf(40)) > 0)
                || (crp != null && crp.compareTo(BigDecimal.valueOf(20)) > 0);

        boolean moderateInflammation = (esr != null && esr.compareTo(BigDecimal.valueOf(20)) > 0)
                || (crp != null && crp.compareTo(BigDecimal.valueOf(10)) > 0);

        if (highInflammation) {
            minimumPain = Math.max(minimumPain, 7);
        } else if (moderateInflammation) {
            minimumPain = Math.max(minimumPain, 5);
        }

        return minimumPain;
    }
}
