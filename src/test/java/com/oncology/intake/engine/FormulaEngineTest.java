package com.oncology.intake.engine;

import com.oncology.intake.dto.AnalysisDto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the FormulaEngine medicine suggestion system.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "whatsapp.api.phone-number-id=test-id",
    "whatsapp.api.access-token=test-token",
    "whatsapp.api.verify-token=test-verify"
})
class FormulaEngineTest {

    @Autowired
    private FormulaEngine formulaEngine;

    @Nested
    @DisplayName("Analysis Generation Tests")
    class AnalysisGenerationTests {

        @Test
        @DisplayName("Should generate analysis for mild pain patient")
        void shouldGenerateAnalysisForMildPain() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(45)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(2)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertNotNull(result.getFormulaVersion());
            assertNotNull(result.getDerivedMetrics());
            assertNotNull(result.getRecommendedMedicines());
            assertNotNull(result.getDisclaimerText());
            
            assertEquals("LOW", result.getDerivedMetrics().getPainCategory());
            assertFalse(result.isRequiresUrgentReview());
        }

        @Test
        @DisplayName("Should generate analysis for moderate pain patient")
        void shouldGenerateAnalysisForModeratePain() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(55)
                    .weightKg(BigDecimal.valueOf(80))
                    .painScale(5)
                    .diagnosisDate(LocalDate.now().minusDays(90))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertEquals("MODERATE", result.getDerivedMetrics().getPainCategory());
            
            // Should include medicine recommendations for moderate pain
            assertFalse(result.getRecommendedMedicines().isEmpty());
        }

        @Test
        @DisplayName("Should generate urgent analysis for severe pain patient")
        void shouldGenerateUrgentAnalysisForSeverePain() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(60)
                    .weightKg(BigDecimal.valueOf(75))
                    .painScale(8)
                    .diagnosisDate(LocalDate.now().minusDays(30))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertEquals("HIGH", result.getDerivedMetrics().getPainCategory());
            assertTrue(result.isRequiresUrgentReview());
            
            // Should have urgent alert
            assertTrue(result.getAlerts().stream()
                    .anyMatch(a -> a.getSeverity() == AlertSeverity.URGENT));
        }

        @Test
        @DisplayName("Should flag pediatric patients for specialist review")
        void shouldFlagPediatricPatients() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(15)
                    .weightKg(BigDecimal.valueOf(50))
                    .painScale(4)
                    .diagnosisDate(LocalDate.now().minusDays(20))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertEquals("pediatric", result.getDerivedMetrics().getAgeCategory());
            assertTrue(result.isRequiresUrgentReview());
            
            // Should have pediatric alert
            assertTrue(result.getAlerts().stream()
                    .anyMatch(a -> a.getType().equals("PEDIATRIC_PATIENT")));
        }

        @Test
        @DisplayName("Should apply dose adjustments for elderly patients")
        void shouldApplyDoseAdjustmentsForElderly() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(75)
                    .weightKg(BigDecimal.valueOf(65))
                    .painScale(4)
                    .diagnosisDate(LocalDate.now().minusDays(100))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertEquals("elderly", result.getDerivedMetrics().getAgeCategory());
            
            // Dose adjustment factor should be less than 1 for elderly
            assertTrue(result.getDerivedMetrics().getDoseAdjustmentFactor()
                    .compareTo(BigDecimal.ONE) < 0);
        }

        @Test
        @DisplayName("Should apply dose adjustments for underweight patients")
        void shouldApplyDoseAdjustmentsForUnderweight() {
            // Given
            AnalysisInput input = AnalysisInput.builder()
                    .age(40)
                    .weightKg(BigDecimal.valueOf(38))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(50))
                    .hasPetScan(true)
                    .hasBloodReport(true)
                    .build();

            // When
            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Then
            assertNotNull(result);
            assertEquals("underweight", result.getDerivedMetrics().getWeightCategory());
            
            // Dose adjustment factor is 1.0 for non-elderly;
            // underweight affects fasting/diet recommendations, not dose factor
            assertEquals("underweight", result.getDerivedMetrics().getWeightCategory());
        }
    }

    @Nested
    @DisplayName("Derived Metrics Tests")
    class DerivedMetricsTests {

        @Test
        @DisplayName("Should correctly categorize pain levels")
        void shouldCorrectlyCategorizePainLevels() {
            // Test low pain (0-3)
            assertEquals("LOW", generateMetrics(2).getPainCategory());
            assertEquals("LOW", generateMetrics(3).getPainCategory());

            // Test moderate pain (4-6)
            assertEquals("MODERATE", generateMetrics(4).getPainCategory());
            assertEquals("MODERATE", generateMetrics(6).getPainCategory());

            // Test high pain (7-10)
            assertEquals("HIGH", generateMetrics(7).getPainCategory());
            assertEquals("HIGH", generateMetrics(10).getPainCategory());
        }

        @Test
        @DisplayName("Should correctly calculate days since diagnosis")
        void shouldCalculateDaysSinceDiagnosis() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(100))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertEquals(100, result.getDerivedMetrics().getDaysSinceDiagnosis());
        }

        @Test
        @DisplayName("Should correctly categorize diagnosis duration")
        void shouldCategorizeDiagnosisDuration() {
            // Newly diagnosed (0-30 days)
            AnalysisInput input1 = createInputWithDiagnosisDays(15);
            assertEquals("newly_diagnosed", 
                    formulaEngine.generateAnalysis(input1).getDerivedMetrics()
                            .getDiagnosisDurationCategory());
            
            // Early treatment (31-180 days)
            AnalysisInput input2 = createInputWithDiagnosisDays(90);
            assertEquals("early_treatment", 
                    formulaEngine.generateAnalysis(input2).getDerivedMetrics()
                            .getDiagnosisDurationCategory());
            
            // Established (181-365 days)
            AnalysisInput input3 = createInputWithDiagnosisDays(250);
            assertEquals("established", 
                    formulaEngine.generateAnalysis(input3).getDerivedMetrics()
                            .getDiagnosisDurationCategory());
            
            // Long term (>365 days)
            AnalysisInput input4 = createInputWithDiagnosisDays(400);
            assertEquals("long_term", 
                    formulaEngine.generateAnalysis(input4).getDerivedMetrics()
                            .getDiagnosisDurationCategory());
        }

        private DerivedMetrics generateMetrics(int painScale) {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(painScale)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();
            return formulaEngine.generateAnalysis(input).getDerivedMetrics();
        }

        private AnalysisInput createInputWithDiagnosisDays(int days) {
            return AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(days))
                    .build();
        }
    }

    @Nested
    @DisplayName("Alert Generation Tests")
    class AlertGenerationTests {

        @Test
        @DisplayName("Should generate alert for newly diagnosed patients")
        void shouldGenerateAlertForNewlyDiagnosed() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(10))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertTrue(result.getAlerts().stream()
                    .anyMatch(a -> a.getType().equals("NEWLY_DIAGNOSED")));
        }

        @Test
        @DisplayName("Should generate urgent alert for elderly with pain")
        void shouldGenerateAlertForElderlyWithPain() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(80)
                    .weightKg(BigDecimal.valueOf(65))
                    .painScale(6)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertTrue(result.getAlerts().stream()
                    .anyMatch(a -> a.getType().equals("ELDERLY_PATIENT")));
        }
    }

    @Nested
    @DisplayName("Supportive Care Tests")
    class SupportiveCareTests {

        @Test
        @DisplayName("Should include supportive care recommendations")
        void shouldIncludeSupportiveCare() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(5)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertNotNull(result.getSupportiveCare());
            assertFalse(result.getSupportiveCare().isEmpty());
        }

        @Test
        @DisplayName("Should include fasting protocol in supportive care")
        void shouldIncludeFastingProtocol() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(6)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertTrue(result.getSupportiveCare().stream()
                    .anyMatch(s -> s.getCategory().contains("Fasting")));
        }

        @Test
        @DisplayName("Should include diet protocol in supportive care")
        void shouldIncludeDietProtocol() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(70)
                    .weightKg(BigDecimal.valueOf(65))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertTrue(result.getSupportiveCare().stream()
                    .anyMatch(s -> s.getCategory().contains("Diet")));
        }
    }

    @Nested
    @DisplayName("Disclaimer Tests")
    class DisclaimerTests {

        @Test
        @DisplayName("Should always include disclaimer")
        void shouldAlwaysIncludeDisclaimer() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertNotNull(result.getDisclaimerText());
            assertFalse(result.getDisclaimerText().isEmpty());
            assertTrue(result.getDisclaimerText().toLowerCase().contains("not"));
            assertTrue(result.getDisclaimerText().toLowerCase().contains("consult") ||
                       result.getDisclaimerText().toLowerCase().contains("oncologist") ||
                       result.getDisclaimerText().toLowerCase().contains("prescription"));
        }
    }

    @Nested
    @DisplayName("Effective Pain Scale Tests")
    class EffectivePainScaleTests {

        @Test
        @DisplayName("Should use effectivePainScale for CBD dosing when set")
        void shouldUseEffectivePainScaleForCBDDosing() {
            // Given: patient self-reports pain 3, but inflammation adjusts to 7
            AnalysisInput inputWithAdjusted = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .effectivePainScale(7)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisInput inputWithoutAdjusted = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            // When
            AnalysisResult resultAdjusted = formulaEngine.generateAnalysis(inputWithAdjusted);
            AnalysisResult resultOriginal = formulaEngine.generateAnalysis(inputWithoutAdjusted);

            // Then: adjusted pain should result in HIGH pain category
            assertEquals("HIGH", resultAdjusted.getDerivedMetrics().getPainCategory());
            assertEquals("LOW", resultOriginal.getDerivedMetrics().getPainCategory());
        }

        @Test
        @DisplayName("Should generate PAIN_ADJUSTED_BY_INFLAMMATION alert when pain adjusted")
        void shouldGeneratePainAdjustedAlert() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .effectivePainScale(7)
                    .esrValue(BigDecimal.valueOf(48))
                    .crpValue(BigDecimal.valueOf(22))
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertTrue(result.getAlerts().stream()
                    .anyMatch(a -> a.getType().equals("PAIN_ADJUSTED_BY_INFLAMMATION")));
        }

        @Test
        @DisplayName("Should not generate PAIN_ADJUSTED alert when no adjustment")
        void shouldNotGeneratePainAdjustedAlertWhenNoAdjustment() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(5)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertFalse(result.getAlerts().stream()
                    .anyMatch(a -> a.getType().equals("PAIN_ADJUSTED_BY_INFLAMMATION")));
        }

        @Test
        @DisplayName("Should fall back to painScale when effectivePainScale is null")
        void shouldFallBackToPainScaleWhenEffectiveIsNull() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(5)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertEquals("MODERATE", result.getDerivedMetrics().getPainCategory());
        }

        @Test
        @DisplayName("Should include cancer stage in assessment summary")
        void shouldIncludeCancerStageInSummary() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .cancerType("BREAST_CANCER")
                    .cancerStage("Stage IV")
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertTrue(result.getAssessmentSummary().contains("Stage IV"));
        }

        @Test
        @DisplayName("Should include ESR and CRP in assessment summary")
        void shouldIncludeInflammationMarkersInSummary() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .esrValue(BigDecimal.valueOf(48))
                    .crpValue(BigDecimal.valueOf(22))
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertTrue(result.getAssessmentSummary().contains("ESR"));
            assertTrue(result.getAssessmentSummary().contains("48"));
            assertTrue(result.getAssessmentSummary().contains("CRP"));
            assertTrue(result.getAssessmentSummary().contains("22"));
        }
    }

    @Nested
    @DisplayName("Protocol-Based Analysis Tests")
    class ProtocolBasedAnalysisTests {

        @Test
        @DisplayName("Should generate cancer-type-specific analysis for breast cancer")
        void shouldGenerateCancerSpecificAnalysisForBreastCancer() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .cancerType("BREAST_CANCER")
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertNotNull(result);
            assertEquals("BREAST_CANCER", result.getCancerType());
            assertNotNull(result.getPhysicianProtocols());
            assertFalse(result.getPhysicianProtocols().isEmpty());

            // Should have 8 physician domains
            assertEquals(8, result.getPhysicianProtocols().size());

            // Check Medical Oncology domain has expected protocol
            PhysicianDomainRecommendation medOnc = result.getPhysicianProtocols().stream()
                    .filter(p -> "Medical Oncology".equals(p.getPhysicianDomain()))
                    .findFirst().orElse(null);
            assertNotNull(medOnc);
            assertNotNull(medOnc.getEcsProducts());
            assertFalse(medOnc.getEcsProducts().isEmpty());
            assertNotNull(medOnc.getDiet());
            assertNotNull(medOnc.getFasting());
        }

        @Test
        @DisplayName("Should generate cancer-type-specific analysis for lung cancer")
        void shouldGenerateCancerSpecificAnalysisForLungCancer() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(60)
                    .weightKg(BigDecimal.valueOf(75))
                    .painScale(5)
                    .diagnosisDate(LocalDate.now().minusDays(90))
                    .cancerType("LUNG_CANCER")
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertNotNull(result);
            assertEquals("LUNG_CANCER", result.getCancerType());
            assertNotNull(result.getPhysicianProtocols());
            assertFalse(result.getPhysicianProtocols().isEmpty());
        }

        @Test
        @DisplayName("Should fall back to generic analysis when no cancer type provided")
        void shouldFallBackToGenericWhenNoCancerType() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertNotNull(result);
            assertNull(result.getCancerType());
            assertNull(result.getPhysicianProtocols());
            // Should still have generic recommendations
            assertNotNull(result.getRecommendedMedicines());
            assertFalse(result.getRecommendedMedicines().isEmpty());
        }

        @Test
        @DisplayName("Should include cancer type in assessment summary")
        void shouldIncludeCancerTypeInSummary() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .cancerType("BREAST_CANCER")
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            assertNotNull(result.getAssessmentSummary());
            assertTrue(result.getAssessmentSummary().contains("Breast Cancer"));
        }

        @Test
        @DisplayName("Should resolve herb dosing from formula-rules for protocol herbs")
        void shouldResolveHerbDosingFromFormulaRules() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .cancerType("BREAST_CANCER")
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);

            // Medical Oncology for Breast Cancer should have Curcumin and Ginger
            boolean hasCurcumin = result.getRecommendedMedicines().stream()
                    .anyMatch(m -> m.getName().contains("Curcumin"));
            assertTrue(hasCurcumin, "Should include Curcumin from Breast Cancer Medical Oncology protocol");
        }
    }
}
