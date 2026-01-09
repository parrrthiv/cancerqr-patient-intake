package com.oncology.intake.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncology.intake.dto.AnalysisDto.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * AI Verification Service
 * 
 * Verifies generated analysis using Claude API before sending to patient.
 * Checks for:
 * - Dosage safety and appropriateness
 * - Potential contraindications
 * - Drug/supplement interactions
 * - Logical consistency
 * - Missing safety alerts
 * 
 * This adds an intelligent safety layer to catch issues the rule-based
 * formula engine might miss.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AIVerificationService {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Value("${ai.verification.enabled:true}")
    private boolean verificationEnabled;

    @Value("${ai.verification.api-key:}")
    private String anthropicApiKey;

    @Value("${ai.verification.model:claude-3-haiku-20240307}")
    private String modelId;

    @Value("${ai.verification.auto-approve-threshold:0.9}")
    private double autoApproveThreshold;

    private WebClient anthropicClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (anthropicApiKey != null && !anthropicApiKey.isEmpty()) {
            this.anthropicClient = WebClient.builder()
                    .baseUrl("https://api.anthropic.com")
                    .defaultHeader("x-api-key", anthropicApiKey)
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .defaultHeader("Content-Type", "application/json")
                    .build();
            log.info("AI Verification Service initialized with model: {}", modelId);
        } else {
            log.warn("AI Verification Service disabled - no API key configured");
            verificationEnabled = false;
        }
    }

    /**
     * Verify analysis before sending to patient
     */
    public Mono<VerificationResult> verifyAnalysis(AnalysisInput patientInput, 
                                                    AnalysisResult analysis) {
        if (!verificationEnabled) {
            log.info("AI verification disabled, auto-approving");
            return Mono.just(VerificationResult.builder()
                    .approved(true)
                    .confidenceScore(1.0)
                    .verificationMethod("DISABLED")
                    .notes("AI verification is disabled")
                    .build());
        }

        // First run rules-based checks (free)
        RulesCheckResult rulesResult = runRulesBasedChecks(patientInput, analysis);
        
        if (rulesResult.hasBlockingIssues()) {
            log.warn("Rules-based check found blocking issues");
            return Mono.just(VerificationResult.builder()
                    .approved(false)
                    .confidenceScore(0.0)
                    .verificationMethod("RULES_BASED")
                    .issues(rulesResult.getIssues())
                    .notes("Failed rules-based safety checks")
                    .requiresHumanReview(true)
                    .build());
        }

        // Then run AI verification
        return runAIVerification(patientInput, analysis)
                .map(aiResult -> {
                    boolean approved = aiResult.getConfidenceScore() >= autoApproveThreshold 
                            && aiResult.getIssues().isEmpty();
                    
                    // Log verification for audit
                    auditService.logVerification(
                            patientInput, 
                            approved, 
                            aiResult.getConfidenceScore(),
                            aiResult.getIssues()
                    );
                    
                    return VerificationResult.builder()
                            .approved(approved)
                            .confidenceScore(aiResult.getConfidenceScore())
                            .verificationMethod("AI_CLAUDE")
                            .issues(aiResult.getIssues())
                            .suggestions(aiResult.getSuggestions())
                            .notes(aiResult.getReasoning())
                            .requiresHumanReview(!approved)
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("AI verification failed, falling back to rules-only", e);
                    return Mono.just(VerificationResult.builder()
                            .approved(rulesResult.getIssues().isEmpty())
                            .confidenceScore(0.7)
                            .verificationMethod("RULES_FALLBACK")
                            .issues(rulesResult.getIssues())
                            .notes("AI verification failed, using rules-based only")
                            .requiresHumanReview(true)
                            .build());
                });
    }

    /**
     * Rules-based verification (free, fast)
     */
    private RulesCheckResult runRulesBasedChecks(AnalysisInput input, AnalysisResult analysis) {
        List<VerificationIssue> issues = new ArrayList<>();

        // Check 1: Pediatric patients should always be flagged
        if (input.getAge() < 18) {
            boolean hasPediatricAlert = analysis.getAlerts().stream()
                    .anyMatch(a -> "PEDIATRIC_PATIENT".equals(a.getType()));
            if (!hasPediatricAlert) {
                issues.add(VerificationIssue.builder()
                        .severity(IssueSeverity.CRITICAL)
                        .category("MISSING_ALERT")
                        .description("Pediatric patient without pediatric alert")
                        .recommendation("Add pediatric specialist referral")
                        .build());
            }
        }

        // Check 2: High pain should have urgent flag
        if (input.getPainScale() >= 8) {
            boolean hasUrgentAlert = analysis.getAlerts().stream()
                    .anyMatch(a -> a.getSeverity() == AlertSeverity.URGENT);
            if (!hasUrgentAlert) {
                issues.add(VerificationIssue.builder()
                        .severity(IssueSeverity.HIGH)
                        .category("MISSING_ALERT")
                        .description("High pain score without urgent alert")
                        .recommendation("Flag for urgent pain management review")
                        .build());
            }
        }

        // Check 3: CBD dose sanity check
        var cbdRec = analysis.getRecommendedMedicines().stream()
                .filter(m -> m.getCategory().contains("Endocannabinoid"))
                .findFirst();
        
        if (cbdRec.isPresent()) {
            String doseStr = cbdRec.get().getDose();
            try {
                int dose = Integer.parseInt(doseStr.replaceAll("[^0-9]", ""));
                // Max reasonable CBD dose check
                if (dose > 150) {
                    issues.add(VerificationIssue.builder()
                            .severity(IssueSeverity.HIGH)
                            .category("DOSAGE")
                            .description("CBD dose exceeds typical maximum: " + dose + "mg")
                            .recommendation("Review and consider capping at 100-150mg")
                            .build());
                }
            } catch (NumberFormatException e) {
                // Skip if can't parse
            }
        }

        // Check 4: Underweight patients should not have fasting
        if (input.getWeightKg().doubleValue() < 45) {
            boolean hasFasting = analysis.getSupportiveCare().stream()
                    .anyMatch(s -> s.getCategory().contains("Fasting") 
                            && !s.getName().contains("Not Recommended"));
            if (hasFasting) {
                issues.add(VerificationIssue.builder()
                        .severity(IssueSeverity.HIGH)
                        .category("CONTRAINDICATION")
                        .description("Fasting recommended for underweight patient")
                        .recommendation("Remove fasting, focus on nutrition")
                        .build());
            }
        }

        // Check 5: Elderly dose adjustment
        if (input.getAge() >= 70) {
            boolean hasElderlyAlert = analysis.getAlerts().stream()
                    .anyMatch(a -> "ELDERLY_PATIENT".equals(a.getType()));
            if (!hasElderlyAlert) {
                issues.add(VerificationIssue.builder()
                        .severity(IssueSeverity.MEDIUM)
                        .category("MISSING_ALERT")
                        .description("Elderly patient without dose adjustment alert")
                        .recommendation("Confirm 50% dose reduction applied")
                        .build());
            }
        }

        // Check 6: Must have disclaimer
        if (analysis.getDisclaimerText() == null || analysis.getDisclaimerText().isEmpty()) {
            issues.add(VerificationIssue.builder()
                    .severity(IssueSeverity.CRITICAL)
                    .category("COMPLIANCE")
                    .description("Missing disclaimer text")
                    .recommendation("Add mandatory wellness disclaimer")
                    .build());
        }

        // Check 7: Reasonable number of recommendations
        int totalRecs = analysis.getRecommendedMedicines().size();
        if (totalRecs > 15) {
            issues.add(VerificationIssue.builder()
                    .severity(IssueSeverity.MEDIUM)
                    .category("OVERLOAD")
                    .description("Too many recommendations: " + totalRecs)
                    .recommendation("Consider prioritizing top recommendations")
                    .build());
        }

        return RulesCheckResult.builder()
                .issues(issues)
                .build();
    }

    /**
     * AI-based verification using Claude API
     */
    private Mono<AIVerificationResponse> runAIVerification(AnalysisInput input, 
                                                            AnalysisResult analysis) {
        String prompt = buildVerificationPrompt(input, analysis);

        Map<String, Object> request = new HashMap<>();
        request.put("model", modelId);
        request.put("max_tokens", 1024);
        request.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));

        return anthropicClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseAIResponse);
    }

    private String buildVerificationPrompt(AnalysisInput input, AnalysisResult analysis) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("""
            You are a medical safety verification assistant. Review the following integrative oncology protocol 
            and identify any safety concerns, contraindications, or issues.
            
            PATIENT PROFILE:
            - Age: %d years
            - Weight: %.1f kg
            - Pain Scale: %d/10
            - Days Since Diagnosis: %d
            
            GENERATED PROTOCOL SUMMARY:
            %s
            
            RECOMMENDATIONS:
            """.formatted(
                input.getAge(),
                input.getWeightKg(),
                input.getPainScale(),
                analysis.getDerivedMetrics().getDaysSinceDiagnosis(),
                analysis.getAssessmentSummary()
            ));

        for (var rec : analysis.getRecommendedMedicines()) {
            prompt.append(String.format("- %s: %s %s\n", rec.getName(), rec.getDose(), rec.getFrequency()));
        }

        prompt.append("""
            
            TASK: Analyze this protocol and respond in JSON format:
            {
                "confidence_score": 0.0-1.0,
                "issues": [
                    {"severity": "CRITICAL|HIGH|MEDIUM|LOW", "category": "...", "description": "..."}
                ],
                "suggestions": ["..."],
                "reasoning": "Brief explanation"
            }
            
            Check for:
            1. Dosage appropriateness for age/weight
            2. Potential herb-drug interactions
            3. Contraindications based on patient profile
            4. Missing safety considerations
            5. Logical consistency of recommendations
            
            Be conservative - flag anything questionable. Patient safety is paramount.
            Respond ONLY with the JSON, no other text.
            """);

        return prompt.toString();
    }

    private AIVerificationResponse parseAIResponse(Map<String, Object> response) {
        try {
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            if (content != null && !content.isEmpty()) {
                String text = (String) content.get(0).get("text");
                
                // Clean up response (remove markdown if present)
                text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
                
                Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
                
                double confidence = ((Number) parsed.getOrDefault("confidence_score", 0.5)).doubleValue();
                String reasoning = (String) parsed.getOrDefault("reasoning", "");
                
                List<VerificationIssue> issues = new ArrayList<>();
                List<Map<String, String>> issuesList = (List<Map<String, String>>) parsed.getOrDefault("issues", List.of());
                for (var issue : issuesList) {
                    issues.add(VerificationIssue.builder()
                            .severity(IssueSeverity.valueOf(issue.getOrDefault("severity", "MEDIUM")))
                            .category(issue.getOrDefault("category", "GENERAL"))
                            .description(issue.getOrDefault("description", ""))
                            .build());
                }
                
                List<String> suggestions = (List<String>) parsed.getOrDefault("suggestions", List.of());
                
                return AIVerificationResponse.builder()
                        .confidenceScore(confidence)
                        .issues(issues)
                        .suggestions(suggestions)
                        .reasoning(reasoning)
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to parse AI response", e);
        }
        
        // Default response on parse failure
        return AIVerificationResponse.builder()
                .confidenceScore(0.5)
                .issues(List.of())
                .suggestions(List.of())
                .reasoning("Could not parse AI response")
                .build();
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class VerificationResult {
        private boolean approved;
        private double confidenceScore;
        private String verificationMethod;
        private List<VerificationIssue> issues;
        private List<String> suggestions;
        private String notes;
        private boolean requiresHumanReview;
    }

    @Data
    @Builder
    public static class VerificationIssue {
        private IssueSeverity severity;
        private String category;
        private String description;
        private String recommendation;
    }

    public enum IssueSeverity {
        CRITICAL,  // Must not send to patient
        HIGH,      // Likely needs human review
        MEDIUM,    // Worth noting
        LOW        // Minor suggestion
    }

    @Data
    @Builder
    private static class RulesCheckResult {
        private List<VerificationIssue> issues;
        
        public boolean hasBlockingIssues() {
            return issues.stream().anyMatch(i -> i.getSeverity() == IssueSeverity.CRITICAL);
        }
    }

    @Data
    @Builder
    private static class AIVerificationResponse {
        private double confidenceScore;
        private List<VerificationIssue> issues;
        private List<String> suggestions;
        private String reasoning;
    }
}
