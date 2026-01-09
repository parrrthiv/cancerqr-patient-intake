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
            
            assertEquals("MILD", result.getDerivedMetrics().getPainCategory());
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
            
            // Should include pain medications for moderate pain
            assertTrue(result.getRecommendedMedicines().stream()
                    .anyMatch(m -> m.getCategory().contains("opioid") || 
                                   m.getCategory().contains("analgesic")));
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
            assertEquals("SEVERE", result.getDerivedMetrics().getPainCategory());
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
            
            // Dose adjustment factor should be less than 1
            assertTrue(result.getDerivedMetrics().getDoseAdjustmentFactor()
                    .compareTo(BigDecimal.ONE) < 0);
        }
    }

    @Nested
    @DisplayName("Derived Metrics Tests")
    class DerivedMetricsTests {

        @Test
        @DisplayName("Should correctly categorize pain levels")
        void shouldCorrectlyCategorizePainLevels() {
            // Test mild pain (0-3)
            assertEquals("MILD", generateMetrics(2).getPainCategory());
            assertEquals("MILD", generateMetrics(3).getPainCategory());
            
            // Test moderate pain (4-6)
            assertEquals("MODERATE", generateMetrics(4).getPainCategory());
            assertEquals("MODERATE", generateMetrics(6).getPainCategory());
            
            // Test severe pain (7-10)
            assertEquals("SEVERE", generateMetrics(7).getPainCategory());
            assertEquals("SEVERE", generateMetrics(10).getPainCategory());
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
                    .anyMatch(a -> a.getType().equals("ELDERLY_WITH_PAIN")));
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
        @DisplayName("Should include antiemetic for moderate-high pain")
        void shouldIncludeAntiemeticForModeratePain() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(50)
                    .weightKg(BigDecimal.valueOf(70))
                    .painScale(6)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertTrue(result.getSupportiveCare().stream()
                    .anyMatch(s -> s.getCategory().equals("Antiemetic")));
        }

        @Test
        @DisplayName("Should include gastric protection for elderly")
        void shouldIncludeGastricProtectionForElderly() {
            AnalysisInput input = AnalysisInput.builder()
                    .age(70)
                    .weightKg(BigDecimal.valueOf(65))
                    .painScale(3)
                    .diagnosisDate(LocalDate.now().minusDays(60))
                    .build();

            AnalysisResult result = formulaEngine.generateAnalysis(input);
            
            assertTrue(result.getSupportiveCare().stream()
                    .anyMatch(s -> s.getCategory().equals("Gastric Protection")));
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
}
