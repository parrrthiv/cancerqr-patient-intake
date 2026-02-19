package com.oncology.intake.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTOs for the medicine analysis and recommendation system.
 */
public class AnalysisDto {

    /**
     * Input data for analysis engine
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisInput {
        private Integer age;
        private BigDecimal weightKg;
        private Integer painScale;
        private LocalDate diagnosisDate;
        private boolean hasPetScan;
        private boolean hasBloodReport;
        private String cancerType;
    }

    /**
     * Complete analysis result
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisResult {
        private String formulaVersion;
        private DerivedMetrics derivedMetrics;
        private List<MedicineRecommendation> recommendedMedicines;
        private List<SupportiveCare> supportiveCare;
        private List<Alert> alerts;
        private String assessmentSummary;
        private String disclaimerText;
        private boolean requiresUrgentReview;
        private String cancerType;
        private List<PhysicianDomainRecommendation> physicianProtocols;
    }

    /**
     * Per-physician-domain protocol recommendation from the cancer protocol matrix
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhysicianDomainRecommendation {
        private String physicianDomain;
        private List<String> ecsProducts;
        private List<MedicineRecommendation> herbs;
        private List<MedicineRecommendation> mushrooms;
        private String diet;
        private String fasting;
        private List<String> repurposedDrugs;
        private List<String> specialty;
        private String lifestyle;
    }

    /**
     * Computed metrics from patient data
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DerivedMetrics {
        private String painCategory;      // MILD, MODERATE, SEVERE
        private String weightCategory;    // underweight, normal, overweight, obese
        private String ageCategory;       // pediatric, adult, elderly
        private Long daysSinceDiagnosis;
        private String diagnosisDurationCategory;  // newly_diagnosed, early_treatment, etc.
        private BigDecimal doseAdjustmentFactor;
        private List<String> appliedRules;
        private Map<String, Object> additionalMetrics;
    }

    /**
     * Individual medicine recommendation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicineRecommendation {
        private String name;
        private String category;
        private String dose;              // e.g., "500 mg"
        private String frequency;         // e.g., "every 6 hours"
        private String maxDailyDose;      // e.g., "4000 mg"
        private Integer durationDays;
        private String notes;
        private boolean requiresSpecialistReview;
        private String adjustmentReason;  // Why dose was adjusted
    }

    /**
     * Supportive care recommendation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportiveCare {
        private String category;          // nutritional, antiemetic, gastric_protection
        private String name;
        private String dose;
        private String frequency;
        private String notes;
    }

    /**
     * Alert/flag from analysis
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alert {
        private AlertSeverity severity;
        private String type;
        private String message;
        private String recommendation;
    }

    public enum AlertSeverity {
        INFO,
        WARNING,
        URGENT
    }

    /**
     * Formatted message for WhatsApp display
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormattedAnalysisMessage {
        private String header;
        private String patientSummary;
        private String medicineSection;
        private String supportiveCareSection;
        private String alertsSection;
        private String disclaimer;
        private String fullMessage;
    }
}
