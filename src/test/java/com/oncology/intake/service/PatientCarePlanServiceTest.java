package com.oncology.intake.service;

import com.oncology.intake.entity.FinalProtocol;
import com.oncology.intake.entity.FinalProtocol.ProtocolStatus;
import com.oncology.intake.repository.FinalProtocolRepository;
import com.oncology.intake.service.PatientCarePlanService.PatientCarePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Locks the legal contract of the patient care plan: medicines/treatments are
 * exposed ONLY from a clinician-APPROVED (or SENT) FinalProtocol, never before.
 * Regressing this would deliver treatment suggestions to a patient pre-review.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientCarePlanService — medicines only after approval")
class PatientCarePlanServiceTest {

    @Mock private FinalProtocolRepository protocolRepository;

    private PatientCarePlanService service;
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PatientCarePlanService(protocolRepository);
    }

    private FinalProtocol protocol(ProtocolStatus status) {
        FinalProtocol p = FinalProtocol.builder().status(status).build();
        p.setEcsProtocol(Map.of("products", List.of("ECS-Forte")));
        p.setHerbProtocol(Map.of("products", List.of("Ashwagandha")));
        p.setMushroomProtocol(Map.of("products", List.of("Reishi")));
        p.setDrugProtocol(Map.of("products", List.of("Metformin")));
        p.setSpecialtyProtocol(Map.of("treatments", List.of("IV Vitamin C")));
        p.setDietFastingProtocol(Map.of("diet", "Anti-Inflammatory",
                "fasting", "14h Overnight", "lifestyle", "No Sugar"));
        return p;
    }

    @Test
    @DisplayName("no protocol → empty")
    void noProtocol() {
        when(protocolRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        assertTrue(service.buildPatientCarePlan(patientId).isEmpty());
    }

    @Test
    @DisplayName("PENDING_APPROVAL → empty (medicines withheld until approved)")
    void notApprovedEmpty() {
        when(protocolRepository.findByPatientId(patientId))
                .thenReturn(Optional.of(protocol(ProtocolStatus.PENDING_APPROVAL)));
        assertTrue(service.buildPatientCarePlan(patientId).isEmpty(),
                "a generated-but-unapproved protocol must NOT expose medicines");
    }

    @Test
    @DisplayName("APPROVED → full plan with medicines + diet")
    void approvedShowsPlan() {
        when(protocolRepository.findByPatientId(patientId))
                .thenReturn(Optional.of(protocol(ProtocolStatus.APPROVED)));

        PatientCarePlan plan = service.buildPatientCarePlan(patientId).orElseThrow();
        assertEquals(List.of("ECS-Forte"), plan.ecsProducts());
        assertEquals(List.of("Metformin"), plan.repurposedDrugs());
        assertEquals(List.of("IV Vitamin C"), plan.specialtyTreatments());
        assertEquals("Anti-Inflammatory", plan.diet());
        assertEquals("14h Overnight", plan.fasting());
        assertFalse(plan.sent());

        String msg = service.formatCarePlanMessage(plan);
        assertTrue(msg.contains("Metformin"), "WhatsApp message includes the medicine");
        assertTrue(msg.contains("Anti-Inflammatory"), "WhatsApp message includes the diet");
        assertTrue(msg.toUpperCase().contains("CARE PLAN"));
    }

    @Test
    @DisplayName("SENT → plan present with sent=true")
    void sentFlag() {
        when(protocolRepository.findByPatientId(patientId))
                .thenReturn(Optional.of(protocol(ProtocolStatus.SENT)));
        assertTrue(service.buildPatientCarePlan(patientId).orElseThrow().sent());
    }
}
