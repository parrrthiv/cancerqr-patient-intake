package com.oncology.intake.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncology.intake.dto.AnalysisDto.AnalysisResult;
import com.oncology.intake.dto.AnalysisDto.MedicineRecommendation;
import com.oncology.intake.dto.AnalysisDto.PhysicianDomainRecommendation;
import com.oncology.intake.dto.AnalysisDto.SupportiveCare;
import com.oncology.intake.engine.FormulaEngine;
import com.oncology.intake.repository.AnalysisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the legal-safety contract of the patient-facing message: it must
 * carry diet/lifestyle guidance only and must NEVER include medicine, dose,
 * CBD/herb/mushroom, fasting, or other treatment content — that is withheld
 * until a clinician reviews it. Regressing this re-opens the unlicensed-medical-
 * advice risk the change was made to close.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalysisService.formatPatientDietMessage — patient-safe (no medicines)")
class AnalysisServicePatientMessageTest {

    @Mock private FormulaEngine formulaEngine;
    @Mock private AnalysisRepository analysisRepository;
    @Mock private PatientIntakeService patientIntakeService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AIVerificationService aiVerificationService;
    @Mock private ReportDataExtractionService reportDataExtractionService;

    @InjectMocks private AnalysisService analysisService;

    private AnalysisResult resultWithMedicines() {
        MedicineRecommendation cbd = MedicineRecommendation.builder()
                .name("CBD Oil").category("Endocannabinoid").dose("100 mg")
                .frequency("twice daily").durationDays(30).build();
        SupportiveCare antiemetic = SupportiveCare.builder()
                .category("antiemetic").name("Ondansetron").dose("8 mg").build();
        PhysicianDomainRecommendation medOnc = PhysicianDomainRecommendation.builder()
                .physicianDomain("MEDICAL_ONCOLOGY")
                .ecsProducts(List.of("ECS-Forte"))
                .diet("Anti-Inflammatory")
                .fasting("14h Overnight")
                .lifestyle("No Sugar")
                .build();
        return AnalysisResult.builder()
                .recommendedMedicines(List.of(cbd))
                .supportiveCare(List.of(antiemetic))
                .physicianProtocols(List.of(medOnc))
                .alerts(List.of())
                .disclaimerText("Consult your oncologist before starting any treatment.")
                .requiresUrgentReview(false)
                .build();
    }

    @Test
    @DisplayName("includes diet + lifestyle guidance")
    void includesDietAndLifestyle() {
        String msg = analysisService.formatPatientDietMessage(resultWithMedicines());
        assertTrue(msg.contains("Anti-Inflammatory"), "should include the diet");
        assertTrue(msg.contains("No Sugar"), "should include the lifestyle");
        assertTrue(msg.toLowerCase().contains("diet"), "should mention diet");
    }

    @Test
    @DisplayName("excludes every medicine / dose / treatment item")
    void excludesMedicineContent() {
        String msg = analysisService.formatPatientDietMessage(resultWithMedicines());
        assertFalse(msg.contains("CBD"), "must not leak medicine name");
        assertFalse(msg.contains("Endocannabinoid"), "must not leak medicine category");
        assertFalse(msg.contains("100 mg"), "must not leak a dose");
        assertFalse(msg.contains("Ondansetron"), "must not leak supportive-care drug");
        assertFalse(msg.contains("ECS-Forte"), "must not leak ECS product");
        assertFalse(msg.contains("14h"), "fasting is withheld");
        assertFalse(msg.contains("💊"), "no medicine section header");
    }

    @Test
    @DisplayName("tells the patient a clinician will review; states it is not a prescription")
    void includesReviewAndNonPrescriptionNote() {
        String msg = analysisService.formatPatientDietMessage(resultWithMedicines());
        assertTrue(msg.toLowerCase().contains("medical team"), "should say a clinician will review");
        assertTrue(msg.toLowerCase().contains("not a prescription"), "should state it is not a prescription");
    }

    @Test
    @DisplayName("handles a generic analysis with no protocol diet (still safe, still helpful)")
    void handlesNoProtocolDiet() {
        AnalysisResult generic = AnalysisResult.builder()
                .recommendedMedicines(List.of())
                .supportiveCare(List.of())
                .physicianProtocols(null)
                .alerts(List.of())
                .disclaimerText("Consult your oncologist.")
                .requiresUrgentReview(true)
                .build();
        String msg = analysisService.formatPatientDietMessage(generic);
        assertTrue(msg.toLowerCase().contains("healthy-eating"), "falls back to generic diet tips");
        assertTrue(msg.toLowerCase().contains("seek prompt medical attention"), "urgent safety line shown");
        assertFalse(msg.contains("💊"));
    }
}
