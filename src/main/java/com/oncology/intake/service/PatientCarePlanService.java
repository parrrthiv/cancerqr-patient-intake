package com.oncology.intake.service;

import com.oncology.intake.entity.FinalProtocol;
import com.oncology.intake.entity.FinalProtocol.ProtocolStatus;
import com.oncology.intake.repository.FinalProtocolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the patient-facing care plan from a clinician-APPROVED FinalProtocol.
 *
 * <p><strong>This is the ONLY path that exposes medicine/treatment content to a
 * patient,</strong> and it returns a plan ONLY once a finalize-capable doctor has
 * approved (or sent) the protocol — so the "reviewed and approved by a clinician
 * first" legal guarantee holds. Before approval, patients still see diet-only
 * guidance ({@link AnalysisService#getPatientDietGuidance}); the strict diet-only
 * pre-approval behaviour is unchanged.
 *
 * <p>Reads the consolidated lists straight off the {@link FinalProtocol} JSON
 * protocols ({@code {products:[...]}} / {@code {treatments:[...]}} /
 * {@code {diet,fasting,lifestyle}}) produced by
 * {@code TumorBoardService.generateFinalProtocol}. Clinician notes
 * ({@code consolidatedNotes}) are intentionally NOT exposed to the patient.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientCarePlanService {

    private final FinalProtocolRepository protocolRepository;

    public record PatientCarePlan(
            List<String> ecsProducts,
            List<String> herbs,
            List<String> mushrooms,
            List<String> repurposedDrugs,
            List<String> specialtyTreatments,
            String diet,
            String fasting,
            String lifestyle,
            boolean sent
    ) {}

    /**
     * The approved care plan for a patient, or empty when no protocol exists or it
     * has not been approved yet (status not APPROVED/SENT).
     */
    @Transactional(readOnly = true)
    public Optional<PatientCarePlan> buildPatientCarePlan(UUID patientId) {
        FinalProtocol p = protocolRepository.findByPatientId(patientId).orElse(null);
        if (p == null || (p.getStatus() != ProtocolStatus.APPROVED
                && p.getStatus() != ProtocolStatus.SENT)) {
            return Optional.empty();
        }
        return Optional.of(new PatientCarePlan(
                listFrom(p.getEcsProtocol(), "products"),
                listFrom(p.getHerbProtocol(), "products"),
                listFrom(p.getMushroomProtocol(), "products"),
                listFrom(p.getDrugProtocol(), "products"),
                listFrom(p.getSpecialtyProtocol(), "treatments"),
                strFrom(p.getDietFastingProtocol(), "diet"),
                strFrom(p.getDietFastingProtocol(), "fasting"),
                strFrom(p.getDietFastingProtocol(), "lifestyle"),
                p.getStatus() == ProtocolStatus.SENT
        ));
    }

    /** A WhatsApp-friendly rendering of the approved plan. */
    public String formatCarePlanMessage(PatientCarePlan plan) {
        StringBuilder m = new StringBuilder();
        m.append("✅ *YOUR CARE PLAN IS READY*\n");
        m.append("━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        m.append("Your medical team has reviewed your case and finalised your treatment plan.\n\n");
        appendList(m, "🌿 Recommended products", plan.ecsProducts());
        appendList(m, "🌱 Herbs", plan.herbs());
        appendList(m, "🍄 Functional mushrooms", plan.mushrooms());
        appendList(m, "💊 Medicines", plan.repurposedDrugs());
        appendList(m, "⭐ Specialty treatments", plan.specialtyTreatments());
        if (notBlank(plan.diet()))      m.append("🥗 *Diet:* ").append(plan.diet()).append("\n");
        if (notBlank(plan.fasting()))   m.append("⏰ *Fasting:* ").append(plan.fasting()).append("\n");
        if (notBlank(plan.lifestyle())) m.append("🌟 *Lifestyle:* ").append(plan.lifestyle()).append("\n");
        m.append("\n━━━━━━━━━━━━━━━━━━━━━━━━\n");
        m.append("⚠️ Take everything exactly as your doctor directs. Contact your care team "
                + "before you start, stop, or change anything, and seek urgent care if your "
                + "symptoms worsen. You can also view this plan anytime in the patient portal.");
        return m.toString();
    }

    private static void appendList(StringBuilder m, String heading, List<String> items) {
        if (items == null || items.isEmpty()) return;
        m.append(heading).append(":\n");
        for (String i : items) m.append("• ").append(i).append("\n");
        m.append("\n");
    }

    @SuppressWarnings("unchecked")
    private static List<String> listFrom(Map<String, Object> map, String key) {
        if (map != null && map.get(key) instanceof List) {
            return new ArrayList<>((List<String>) map.get(key));
        }
        return new ArrayList<>();
    }

    private static String strFrom(Map<String, Object> map, String key) {
        Object v = (map == null) ? null : map.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
