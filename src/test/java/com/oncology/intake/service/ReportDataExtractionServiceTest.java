package com.oncology.intake.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReportDataExtractionService regex extraction and pain adjustment logic.
 * These tests use the service's extraction methods directly without Spring context.
 */
class ReportDataExtractionServiceTest {

    private ReportDataExtractionService service;

    @BeforeEach
    void setUp() {
        // Construct with null dependencies - we only test the pure extraction methods
        service = new ReportDataExtractionService(null, null, null);
    }

    @Nested
    @DisplayName("Cancer Stage Extraction Tests")
    class CancerStageExtractionTests {

        @Test
        @DisplayName("Should extract Stage IV")
        void shouldExtractStageIV() {
            assertEquals("Stage IV", service.extractCancerStage("Patient diagnosed with Stage IV lung cancer"));
        }

        @Test
        @DisplayName("Should extract Stage IIIB")
        void shouldExtractStageIIIB() {
            assertEquals("Stage IIIB", service.extractCancerStage("Findings consistent with Stage IIIB disease"));
        }

        @Test
        @DisplayName("Should extract Stage II")
        void shouldExtractStageII() {
            assertEquals("Stage II", service.extractCancerStage("Classified as Stage II breast cancer"));
        }

        @Test
        @DisplayName("Should extract Stage I")
        void shouldExtractStageI() {
            assertEquals("Stage I", service.extractCancerStage("Early detection: Stage I"));
        }

        @Test
        @DisplayName("Should extract Stage IVA")
        void shouldExtractStageIVA() {
            assertEquals("Stage IVA", service.extractCancerStage("Assessment: Stage IVA with metastasis"));
        }

        @Test
        @DisplayName("Should return null when no stage found")
        void shouldReturnNullWhenNoStage() {
            assertNull(service.extractCancerStage("No staging information in this report"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(service.extractCancerStage(null));
        }

        @Test
        @DisplayName("Should return null for empty input")
        void shouldReturnNullForEmptyInput() {
            assertNull(service.extractCancerStage(""));
        }
    }

    @Nested
    @DisplayName("ESR Extraction Tests")
    class ESRExtractionTests {

        @Test
        @DisplayName("Should extract ESR with colon separator")
        void shouldExtractESRWithColon() {
            assertEquals(new BigDecimal("48"), service.extractESR("ESR: 48 mm/hr"));
        }

        @Test
        @DisplayName("Should extract ESR with space separator")
        void shouldExtractESRWithSpace() {
            assertEquals(new BigDecimal("48"), service.extractESR("ESR 48 mm/hr"));
        }

        @Test
        @DisplayName("Should extract ESR with decimal value")
        void shouldExtractESRWithDecimal() {
            assertEquals(new BigDecimal("48.5"), service.extractESR("ESR: 48.5 mm/hr"));
        }

        @Test
        @DisplayName("Should return null when no ESR found")
        void shouldReturnNullWhenNoESR() {
            assertNull(service.extractESR("Hemoglobin: 12.5 g/dL"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(service.extractESR(null));
        }
    }

    @Nested
    @DisplayName("CRP Extraction Tests")
    class CRPExtractionTests {

        @Test
        @DisplayName("Should extract CRP with colon separator")
        void shouldExtractCRPWithColon() {
            assertEquals(new BigDecimal("22"), service.extractCRP("CRP: 22 mg/L"));
        }

        @Test
        @DisplayName("Should extract CRP with lowercase L")
        void shouldExtractCRPWithLowercaseL() {
            assertEquals(new BigDecimal("22"), service.extractCRP("CRP: 22 mg/l"));
        }

        @Test
        @DisplayName("Should extract CRP with decimal value")
        void shouldExtractCRPWithDecimal() {
            assertEquals(new BigDecimal("15.5"), service.extractCRP("CRP 15.5 mg/L"));
        }

        @Test
        @DisplayName("Should extract CRP from C-Reactive Protein (CRP) format")
        void shouldExtractCRPFromFullName() {
            assertEquals(new BigDecimal("22"), service.extractCRP("C-Reactive Protein (CRP) 22 mg/L"));
        }

        @Test
        @DisplayName("Should return null when no CRP found")
        void shouldReturnNullWhenNoCRP() {
            assertNull(service.extractCRP("WBC: 8000 /uL"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(service.extractCRP(null));
        }
    }

    @Nested
    @DisplayName("Pain Adjustment Logic Tests")
    class PainAdjustmentTests {

        @Test
        @DisplayName("Should adjust pain to 7 when ESR > 40")
        void shouldAdjustPainForHighESR() {
            int result = service.calculateEffectivePainScale(3, BigDecimal.valueOf(48), null);
            assertEquals(7, result);
        }

        @Test
        @DisplayName("Should adjust pain to 7 when CRP > 20")
        void shouldAdjustPainForHighCRP() {
            int result = service.calculateEffectivePainScale(3, null, BigDecimal.valueOf(22));
            assertEquals(7, result);
        }

        @Test
        @DisplayName("Should adjust pain to 5 when ESR > 20")
        void shouldAdjustPainForModerateESR() {
            int result = service.calculateEffectivePainScale(3, BigDecimal.valueOf(25), null);
            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should adjust pain to 5 when CRP > 10")
        void shouldAdjustPainForModerateCRP() {
            int result = service.calculateEffectivePainScale(3, null, BigDecimal.valueOf(15));
            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should not reduce self-reported pain")
        void shouldNotReduceSelfReportedPain() {
            int result = service.calculateEffectivePainScale(8, BigDecimal.valueOf(10), BigDecimal.valueOf(5));
            assertEquals(8, result);
        }

        @Test
        @DisplayName("Should keep self-reported pain when no inflammation")
        void shouldKeepSelfReportedPainWhenNoInflammation() {
            int result = service.calculateEffectivePainScale(3, BigDecimal.valueOf(10), BigDecimal.valueOf(5));
            assertEquals(3, result);
        }

        @Test
        @DisplayName("Should handle null ESR and CRP")
        void shouldHandleNullMarkers() {
            int result = service.calculateEffectivePainScale(5, null, null);
            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should prefer higher adjustment when both markers are high")
        void shouldPreferHigherAdjustment() {
            int result = service.calculateEffectivePainScale(3, BigDecimal.valueOf(45), BigDecimal.valueOf(25));
            assertEquals(7, result);
        }

        @Test
        @DisplayName("Should not adjust when self-reported pain already exceeds threshold")
        void shouldNotAdjustWhenPainAlreadyHigh() {
            int result = service.calculateEffectivePainScale(9, BigDecimal.valueOf(45), BigDecimal.valueOf(25));
            assertEquals(9, result);
        }
    }
}
