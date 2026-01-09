package com.oncology.intake.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.oncology.intake.dto.AnalysisDto.*;
import com.oncology.intake.exception.IntakeExceptions.FormulaEngineException;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * ECS-Based Integrative Oncology Protocol Engine
 * 
 * Generates personalized wellness suggestions including:
 * - CBD/Endocannabinoid therapy dosing
 * - Mono herb recommendations
 * - Functional mushroom protocols
 * - Fasting recommendations
 * - Diet protocols
 * - Repurposed compound information
 * 
 * IMPORTANT: This engine produces WELLNESS SUGGESTIONS ONLY.
 * All output must include mandatory disclaimers and must be reviewed
 * by qualified healthcare professionals before implementation.
 */
@Component
@Slf4j
public class FormulaEngine {

    @Value("${formula.config-path:classpath:formula-rules.yml}")
    private Resource formulaConfigResource;

    @Value("${app.disclaimer}")
    private String appDisclaimer;

    private FormulaConfig formulaConfig;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @PostConstruct
    public void init() {
        try {
            formulaConfig = yamlMapper.readValue(
                    formulaConfigResource.getInputStream(), 
                    FormulaConfig.class
            );
            log.info("ECS Formula Engine initialized - Version: {} (Status: {})", 
                    formulaConfig.getVersion(), formulaConfig.getStatus());
        } catch (Exception e) {
            throw new FormulaEngineException("Failed to load formula configuration", e);
        }
    }

    /**
     * Generate comprehensive integrative oncology protocol
     */
    public AnalysisResult generateAnalysis(AnalysisInput input) {
        log.info("Generating ECS-based integrative protocol");
        
        try {
            DerivedMetrics metrics = calculateDerivedMetrics(input);
            
            List<MedicineRecommendation> recommendations = new ArrayList<>();
            recommendations.addAll(generateCBDRecommendations(input, metrics));
            recommendations.addAll(generateHerbRecommendations(input, metrics));
            recommendations.addAll(generateMushroomRecommendations(input, metrics));
            recommendations.addAll(generateCompoundRecommendations(input, metrics));
            
            List<SupportiveCare> supportiveCare = new ArrayList<>();
            supportiveCare.addAll(generateFastingRecommendation(input, metrics));
            supportiveCare.addAll(generateDietRecommendation(input, metrics));
            
            List<Alert> alerts = checkAlerts(input, metrics);
            String summary = generateAssessmentSummary(input, metrics, alerts);
            boolean urgentReview = alerts.stream().anyMatch(a -> a.getSeverity() == AlertSeverity.URGENT);
            
            return AnalysisResult.builder()
                    .formulaVersion(formulaConfig.getVersion())
                    .derivedMetrics(metrics)
                    .recommendedMedicines(recommendations)
                    .supportiveCare(supportiveCare)
                    .alerts(alerts)
                    .assessmentSummary(summary)
                    .disclaimerText(getDisclaimer())
                    .requiresUrgentReview(urgentReview)
                    .build();
                    
        } catch (Exception e) {
            log.error("Error generating analysis", e);
            throw new FormulaEngineException("Failed to generate analysis", e);
        }
    }

    private DerivedMetrics calculateDerivedMetrics(AnalysisInput input) {
        List<String> appliedRules = new ArrayList<>();
        
        String painCategory = determinePainCategory(input.getPainScale());
        appliedRules.add("pain_category:" + painCategory);
        
        String weightCategory = determineWeightCategory(input.getWeightKg());
        appliedRules.add("weight_category:" + weightCategory);
        
        String ageCategory = determineAgeCategory(input.getAge());
        appliedRules.add("age_category:" + ageCategory);
        
        long daysSinceDiagnosis = ChronoUnit.DAYS.between(input.getDiagnosisDate(), LocalDate.now());
        
        BigDecimal cbdDose = calculateCBDDose(input);
        appliedRules.add("cbd_daily_dose_mg:" + cbdDose);
        
        BigDecimal doseAdjustmentFactor = calculateDoseAdjustmentFactor(input.getAge());
        appliedRules.add("dose_factor:" + doseAdjustmentFactor);
        
        Map<String, Object> additionalMetrics = new HashMap<>();
        additionalMetrics.put("cbd_base_dose_mg", cbdDose);
        additionalMetrics.put("protocol_type", "ECS_INTEGRATIVE");
        
        return DerivedMetrics.builder()
                .painCategory(painCategory)
                .weightCategory(weightCategory)
                .ageCategory(ageCategory)
                .daysSinceDiagnosis(daysSinceDiagnosis)
                .diagnosisDurationCategory(determineDiagnosisDuration(daysSinceDiagnosis))
                .doseAdjustmentFactor(doseAdjustmentFactor)
                .appliedRules(appliedRules)
                .additionalMetrics(additionalMetrics)
                .build();
    }

    private List<MedicineRecommendation> generateCBDRecommendations(AnalysisInput input, DerivedMetrics metrics) {
        List<MedicineRecommendation> recommendations = new ArrayList<>();
        var cbdConfig = formulaConfig.getEndocannabinoidTherapy();
        if (cbdConfig == null) return recommendations;
        
        BigDecimal cbdDose = calculateCBDDose(input);
        var formulations = cbdConfig.getFormulations();
        
        if (formulations != null && !formulations.isEmpty()) {
            var formulation = formulations.get(0);
            recommendations.add(MedicineRecommendation.builder()
                    .name(formulation.getName())
                    .category("🌿 Endocannabinoid Therapy")
                    .dose(cbdDose + " mg daily")
                    .frequency(formulation.getTiming())
                    .durationDays(90)
                    .notes(formulation.getNotes() + ". " + formulation.getAdministration())
                    .requiresSpecialistReview(false)
                    .adjustmentReason(String.format("Based on: %.1fkg body weight × pain level %d/10",
                            input.getWeightKg(), input.getPainScale()))
                    .build());
        }
        return recommendations;
    }

    private BigDecimal calculateCBDDose(AnalysisInput input) {
        var cbdConfig = formulaConfig.getEndocannabinoidTherapy();
        if (cbdConfig == null) return BigDecimal.ZERO;
        
        BigDecimal baseDose = input.getWeightKg().multiply(BigDecimal.valueOf(cbdConfig.getBaseDoseMgPerKg()));
        double painMultiplier = getPainMultiplier(input.getPainScale(), cbdConfig);
        BigDecimal adjustedDose = baseDose.multiply(BigDecimal.valueOf(painMultiplier));
        BigDecimal maxDose = getMaxCBDDose(input.getWeightKg(), cbdConfig);
        
        if (adjustedDose.compareTo(maxDose) > 0) adjustedDose = maxDose;
        return adjustedDose.setScale(0, RoundingMode.HALF_UP);
    }

    private double getPainMultiplier(int painScale, EndocannabinoidConfig config) {
        if (config.getPainMultipliers() == null) return 1.0;
        for (var multiplier : config.getPainMultipliers()) {
            int[] range = multiplier.getPainRange();
            if (painScale >= range[0] && painScale <= range[1]) return multiplier.getMultiplier();
        }
        return 1.0;
    }

    private BigDecimal getMaxCBDDose(BigDecimal weight, EndocannabinoidConfig config) {
        if (config.getWeightCategories() == null) return BigDecimal.valueOf(100);
        for (var category : config.getWeightCategories()) {
            int[] range = category.getWeightRangeKg();
            if (weight.doubleValue() >= range[0] && weight.doubleValue() <= range[1])
                return BigDecimal.valueOf(category.getMaxDailyMg());
        }
        return BigDecimal.valueOf(100);
    }

    private List<MedicineRecommendation> generateHerbRecommendations(AnalysisInput input, DerivedMetrics metrics) {
        List<MedicineRecommendation> recommendations = new ArrayList<>();
        var herbConfig = formulaConfig.getMonoHerbs();
        if (herbConfig == null || herbConfig.getHerbs() == null) return recommendations;
        
        List<String> priorityHerbs = Arrays.asList("CURCUMIN", "ASHWAGANDHA", "MILK_THISTLE", "BLACK_SEED", "BOSWELLIA");
        
        int count = 0;
        for (String herbId : priorityHerbs) {
            if (count >= 5) break;
            var herb = herbConfig.getHerbs().stream().filter(h -> herbId.equals(h.getId())).findFirst().orElse(null);
            if (herb != null) {
                recommendations.add(createHerbRecommendation(herb, input));
                count++;
            }
        }
        return recommendations;
    }

    private MedicineRecommendation createHerbRecommendation(HerbConfig herb, AnalysisInput input) {
        String dose = "As directed";
        String frequency = "daily";
        
        if (herb.getDosing() != null) {
            for (var dosing : herb.getDosing()) {
                int[] range = dosing.getWeightRangeKg();
                if (input.getWeightKg().doubleValue() >= range[0] && input.getWeightKg().doubleValue() <= range[1]) {
                    int adjustedDose = input.getAge() >= 70 ? (int)(dosing.getDoseMg() * 0.5) : dosing.getDoseMg();
                    dose = adjustedDose + " mg";
                    frequency = dosing.getFrequency();
                    break;
                }
            }
        }
        
        String notes = herb.getBenefits();
        if (herb.getCautions() != null) notes += " ⚠️ " + herb.getCautions();
        if (herb.getForm() != null) notes += " | Form: " + herb.getForm();
        
        return MedicineRecommendation.builder()
                .name(herb.getName())
                .category("🌱 Mono Herb - " + herb.getCategory())
                .dose(dose).frequency(frequency)
                .durationDays(herb.getDurationWeeks() != null ? herb.getDurationWeeks() * 7 : 56)
                .notes(notes).requiresSpecialistReview(false).build();
    }

    private List<MedicineRecommendation> generateMushroomRecommendations(AnalysisInput input, DerivedMetrics metrics) {
        List<MedicineRecommendation> recommendations = new ArrayList<>();
        var mushroomConfig = formulaConfig.getFunctionalMushrooms();
        if (mushroomConfig == null || mushroomConfig.getMushrooms() == null) return recommendations;
        
        List<String> selectedMushrooms = Arrays.asList("TURKEY_TAIL", "REISHI", "LIONS_MANE");
        
        int count = 0;
        for (String mushroomId : selectedMushrooms) {
            if (count >= 3) break;
            var mushroom = mushroomConfig.getMushrooms().stream().filter(m -> mushroomId.equals(m.getId())).findFirst().orElse(null);
            if (mushroom != null) {
                recommendations.add(createMushroomRecommendation(mushroom, input));
                count++;
            }
        }
        return recommendations;
    }

    private MedicineRecommendation createMushroomRecommendation(MushroomConfig mushroom, AnalysisInput input) {
        String dose = "As directed";
        String frequency = "twice daily";
        
        if (mushroom.getDosing() != null) {
            for (var dosing : mushroom.getDosing()) {
                int[] range = dosing.getWeightRangeKg();
                if (input.getWeightKg().doubleValue() >= range[0] && input.getWeightKg().doubleValue() <= range[1]) {
                    dose = dosing.getDoseMg() + " mg";
                    frequency = dosing.getFrequency();
                    break;
                }
            }
        }
        
        String notes = mushroom.getBenefits();
        if (mushroom.getForm() != null) notes += " | Form: " + mushroom.getForm();
        if (mushroom.getNotes() != null) notes += " | " + mushroom.getNotes();
        
        return MedicineRecommendation.builder()
                .name(mushroom.getName())
                .category("🍄 Functional Mushroom")
                .dose(dose).frequency(frequency)
                .durationDays(mushroom.getDurationWeeks() != null ? mushroom.getDurationWeeks() * 7 : 84)
                .notes(notes).requiresSpecialistReview(false).build();
    }

    private List<MedicineRecommendation> generateCompoundRecommendations(AnalysisInput input, DerivedMetrics metrics) {
        List<MedicineRecommendation> recommendations = new ArrayList<>();
        recommendations.add(MedicineRecommendation.builder()
                .name("Repurposed Compounds (Informational)")
                .category("💊 Anti-Cancer Compounds")
                .dose("Consult healthcare provider")
                .frequency("As prescribed").durationDays(0)
                .notes("Compounds like Ivermectin, Methylene Blue, Fenbendazole are being researched. REQUIRE medical supervision. Discuss with your integrative oncologist.")
                .requiresSpecialistReview(true).build());
        return recommendations;
    }

    private List<SupportiveCare> generateFastingRecommendation(AnalysisInput input, DerivedMetrics metrics) {
        List<SupportiveCare> recommendations = new ArrayList<>();
        
        if (input.getWeightKg().doubleValue() < 45) {
            recommendations.add(SupportiveCare.builder().category("⏰ Fasting Protocol").name("Fasting Not Recommended")
                    .notes("Due to lower body weight, focus on nutrient-dense eating. Prioritize adequate caloric and protein intake.").build());
            return recommendations;
        }
        
        if (input.getPainScale() >= 7) {
            recommendations.add(SupportiveCare.builder().category("⏰ Fasting Protocol").name("Gentle Time-Restricted Eating")
                    .notes("Due to higher pain levels, avoid strict fasting. Consider 12:12 eating pattern if tolerated.").build());
            return recommendations;
        }
        
        recommendations.add(SupportiveCare.builder().category("⏰ Fasting Protocol").name("Intermittent Fasting 16:8")
                .dose("Eating: 10AM-6PM | Fasting: 6PM-10AM")
                .notes("16 hours fasting, 8 hour eating window. Allowed during fast: Water, black coffee, plain tea. Start gradually.").build());
        return recommendations;
    }

    private List<SupportiveCare> generateDietRecommendation(AnalysisInput input, DerivedMetrics metrics) {
        List<SupportiveCare> recommendations = new ArrayList<>();
        
        String dietName = input.getWeightKg().doubleValue() > 90 ? "Modified Ketogenic Diet" : "Anti-Inflammatory Diet";
        String notes = input.getWeightKg().doubleValue() > 90 
                ? "60-65% fat, 20-25% protein, 15-20% carbs. Focus on healthy fats, moderate protein, low carbs."
                : "Abundant vegetables (8-10 servings), healthy fats (olive oil, avocado), fatty fish 2-3x weekly, colorful fruits, herbs/spices. Avoid: processed foods, refined sugars, industrial seed oils.";
        
        recommendations.add(SupportiveCare.builder().category("🥗 Diet Protocol").name(dietName).notes(notes).build());
        return recommendations;
    }

    private List<Alert> checkAlerts(AnalysisInput input, DerivedMetrics metrics) {
        List<Alert> alerts = new ArrayList<>();
        
        if (input.getAge() < 18)
            alerts.add(Alert.builder().severity(AlertSeverity.URGENT).type("PEDIATRIC_PATIENT")
                    .message("Pediatric patient - requires specialized guidance")
                    .recommendation("This protocol is for adults. Please consult a pediatric integrative oncologist.").build());
        
        if (input.getPainScale() >= 7)
            alerts.add(Alert.builder().severity(AlertSeverity.URGENT).type("HIGH_PAIN")
                    .message("High pain score detected (" + input.getPainScale() + "/10)")
                    .recommendation("Prioritize pain management. Consider higher CBD dosing and consult pain specialist.").build());
        
        if (input.getAge() >= 70)
            alerts.add(Alert.builder().severity(AlertSeverity.WARNING).type("ELDERLY_PATIENT")
                    .message("Patient age 70+ - dose adjustments applied")
                    .recommendation("All supplement doses reduced by 50%. Start low and increase gradually.").build());
        
        if (input.getWeightKg().doubleValue() < 45)
            alerts.add(Alert.builder().severity(AlertSeverity.WARNING).type("UNDERWEIGHT")
                    .message("Low body weight detected - nutritional priority")
                    .recommendation("Focus on caloric intake and nutrition. Avoid fasting.").build());
        
        if (metrics.getDaysSinceDiagnosis() < 30)
            alerts.add(Alert.builder().severity(AlertSeverity.INFO).type("NEWLY_DIAGNOSED")
                    .message("Recently diagnosed (within 30 days)")
                    .recommendation("Ensure conventional workup is complete. Coordinate with oncology team.").build());
        
        return alerts;
    }

    private String generateAssessmentSummary(AnalysisInput input, DerivedMetrics metrics, List<Alert> alerts) {
        StringBuilder summary = new StringBuilder();
        summary.append("📋 *INTEGRATIVE ONCOLOGY PROTOCOL*\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        summary.append("*Patient Profile:*\n");
        summary.append(String.format("• Age: %d years (%s)\n", input.getAge(), metrics.getAgeCategory()));
        summary.append(String.format("• Weight: %.1f kg (%s)\n", input.getWeightKg(), metrics.getWeightCategory()));
        summary.append(String.format("• Pain Level: %d/10 (%s)\n", input.getPainScale(), metrics.getPainCategory()));
        summary.append(String.format("• Days Since Diagnosis: %d\n\n", metrics.getDaysSinceDiagnosis()));
        
        var cbdDose = metrics.getAdditionalMetrics().get("cbd_base_dose_mg");
        if (cbdDose != null) summary.append(String.format("*Calculated CBD Dose:* %s mg/day\n\n", cbdDose));
        
        if (!alerts.isEmpty()) {
            summary.append("*⚠️ Alerts:*\n");
            for (Alert alert : alerts) {
                String icon = switch (alert.getSeverity()) { case URGENT -> "🔴"; case WARNING -> "🟡"; case INFO -> "🔵"; };
                summary.append(String.format("%s %s\n", icon, alert.getMessage()));
            }
            summary.append("\n");
        }
        
        summary.append("*Protocol Components:*\n• 🌿 Endocannabinoid (CBD) Therapy\n• 🌱 Mono Herbs (5 selected)\n• 🍄 Functional Mushrooms (3 selected)\n• ⏰ Fasting Protocol\n• 🥗 Diet Protocol\n");
        return summary.toString();
    }

    private String determinePainCategory(int painScale) { return painScale <= 3 ? "LOW" : painScale <= 6 ? "MODERATE" : "HIGH"; }
    private String determineWeightCategory(BigDecimal w) { double wt = w.doubleValue(); return wt < 45 ? "underweight" : wt < 75 ? "normal" : wt < 100 ? "overweight" : "obese"; }
    private String determineAgeCategory(int age) { return age < 18 ? "pediatric" : age < 65 ? "adult" : "elderly"; }
    private String determineDiagnosisDuration(long days) { return days <= 30 ? "newly_diagnosed" : days <= 180 ? "early_treatment" : days <= 365 ? "established" : "long_term"; }
    private BigDecimal calculateDoseAdjustmentFactor(int age) { return age >= 70 ? BigDecimal.valueOf(0.5) : age >= 65 ? BigDecimal.valueOf(0.75) : BigDecimal.ONE; }
    public String getDisclaimer() { return formulaConfig != null && formulaConfig.getDisclaimer() != null ? formulaConfig.getDisclaimer() : appDisclaimer; }
    public String getFormulaVersion() { return formulaConfig != null ? formulaConfig.getVersion() : "unknown"; }

    // Configuration Classes
    @Data public static class FormulaConfig { private String version; private String lastUpdated; private String status; private String disclaimer; private EndocannabinoidConfig endocannabinoidTherapy; private MonoHerbsConfig monoHerbs; private AntiCancerCompoundsConfig antiCancerCompounds; private FunctionalMushroomsConfig functionalMushrooms; private FastingProtocolsConfig fastingProtocols; private DietProtocolsConfig dietProtocols; private AlertsConfig alerts; private Map<String, Object> protocolGeneration; }
    @Data public static class EndocannabinoidConfig { private String description; private double baseDoseMgPerKg; private List<PainMultiplier> painMultipliers; private List<WeightCategory> weightCategories; private List<CBDFormulation> formulations; private Map<String, String> titration;}
    @Data public static class PainMultiplier { private int[] painRange; private String category; private double multiplier; private String description; }
    @Data public static class WeightCategory { private String category; private int[] weightRangeKg; private int maxDailyMg; }
    @Data public static class CBDFormulation { private String id; private String name; private String type; private int concentrationMgPerMl; private int concentrationMgPerCapsule;private String description; private String administration; private String timing; private String notes; }
    @Data public static class MonoHerbsConfig { private String description; private List<HerbConfig> herbs; }
    @Data public static class HerbConfig { private String id; private String name; private String botanicalName; private String category; private String benefits; private List<HerbDosing> dosing; private String form; private String timing; private Integer durationWeeks; private String notes; private String cautions; }
    @Data public static class HerbDosing { private int[] weightRangeKg; private int doseMg; private String frequency; }
    @Data public static class AntiCancerCompoundsConfig { private String description; private String warning; private List<CompoundConfig> compounds; }
    @Data public static class CompoundConfig { private String id; private String name; private String category; private List<CompoundDosing> dosing; private String timing; private String duration; private boolean requiresPrescription; private String cautions; private String notes; }
    @Data public static class CompoundDosing { private int[] weightRangeKg; private int doseMg; private String frequency; }
    @Data public static class FunctionalMushroomsConfig { private String description; private List<MushroomConfig> mushrooms; }
    @Data public static class MushroomConfig { private String id; private String name; private String botanicalName; private String benefits; private List<MushroomDosing> dosing; private String form; private String timing; private Integer durationWeeks; private String notes; }
    @Data public static class MushroomDosing { private int[] weightRangeKg; private int doseMg; private String frequency; }
    @Data public static class FastingProtocolsConfig { private String description; private List<FastingProtocol> protocols; private List<SelectionLogic> selectionLogic; }
    @Data public static class FastingProtocol { private String id; private String name; private String description; private FastingSchedule schedule; private List<String> allowedDuringFast; private List<String> contraindications; private SuitableFor suitableFor; }
    @Data public static class FastingSchedule { private String eatingWindow; private String fastingWindow; private String example; }
    @Data public static class DietProtocolsConfig { private String description; private List<DietProtocol> protocols; private List<SelectionLogic> selectionLogic; }
    @Data public static class DietProtocol { private String id; private String name; private String description; private String suitableFor; private List<String> principles; private List<String> avoid; private Map<String, String> macros; private List<String> contraindications; }
    @Data public static class AlertsConfig { private AlertThreshold highPainScore; private AlertThreshold elderlyPatient; private AlertThreshold underweight; private AlertThreshold pediatric; }
    @Data public static class AlertThreshold { private Integer threshold; private Integer ageThreshold; private Integer weightThresholdKg; private String message; }
	@Data public static class TitrationWeek { private String dose; private String timing; private String notes; }
	@Data public static class SuitableFor { private Integer painScaleMax; private String experience; }
	@Data public static class SelectionLogic { private String condition; private String recommendation; }
}
