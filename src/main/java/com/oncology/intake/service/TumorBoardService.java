package com.oncology.intake.service;

import com.oncology.intake.entity.*;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import com.oncology.intake.entity.TumorBoardReview.ReviewStatus;
import com.oncology.intake.entity.FinalProtocol.ProtocolStatus;
import com.oncology.intake.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing Tumor Board reviews and final protocols.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TumorBoardService {

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final TumorBoardReviewRepository reviewRepository;
    private final FinalProtocolRepository protocolRepository;
    private final AuditService auditService;

    private static final int REQUIRED_REVIEWS = 8;

    /**
     * Create review tasks for all 8 physician domains when a patient completes intake
     */
    @Transactional
    public void createReviewTasksForPatient(UUID patientId) {
        log.info("Creating tumor board review tasks for patient: {}", patientId);

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found: " + patientId));

        // Create a review task for each physician domain
        for (PhysicianDomain domain : PhysicianDomain.values()) {
            if (domain == PhysicianDomain.ADMIN) continue; // Skip admin

            // Check if review already exists
            if (reviewRepository.findByPatientIdAndPhysicianDomain(patientId, domain).isEmpty()) {
                TumorBoardReview review = TumorBoardReview.builder()
                        .patient(patient)
                        .doctor(null) // Will be assigned when doctor picks it up
                        .physicianDomain(domain)
                        .status(ReviewStatus.PENDING)
                        .build();

                reviewRepository.save(review);
                log.debug("Created review task for domain: {}", domain);
            }
        }

        auditService.logSystemAction(patientId, AuditLog.AuditAction.TUMOR_BOARD_CREATED,
                "Review tasks created for all 8 physician domains");
    }

    /**
     * Get pending reviews for a specific doctor
     */
    public List<TumorBoardReview> getPendingReviewsForDoctor(UUID doctorId) {
        return reviewRepository.findByDoctorIdAndStatus(doctorId, ReviewStatus.PENDING);
    }

    /**
     * Get all reviews for a doctor's domain (unassigned)
     */
    public List<TumorBoardReview> getUnassignedReviewsForDomain(PhysicianDomain domain) {
        // Get all pending reviews for this domain that don't have a doctor assigned
        return reviewRepository.findAll().stream()
                .filter(r -> r.getPhysicianDomain() == domain)
                .filter(r -> r.getDoctor() == null)
                .filter(r -> r.getStatus() == ReviewStatus.PENDING)
                .toList();
    }

    /**
     * Assign a review to a doctor
     */
    @Transactional
    public TumorBoardReview assignReviewToDoctor(UUID reviewId, UUID doctorId) {
        TumorBoardReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Review not found"));

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        review.setDoctor(doctor);
        review.setStatus(ReviewStatus.IN_PROGRESS);

        return reviewRepository.save(review);
    }

    /**
     * Submit a doctor's review
     */
    @Transactional
    public TumorBoardReview submitReview(UUID reviewId, Map<String, Object> selectedProtocols,
                                          String notes, String recommendations) {
        TumorBoardReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Review not found"));

        review.setSelectedProtocols(selectedProtocols);
        review.setNotes(notes);
        review.setRecommendations(recommendations);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setReviewedAt(LocalDateTime.now());

        TumorBoardReview savedReview = reviewRepository.save(review);

        // Check if all reviews are complete
        checkAndGenerateFinalProtocol(review.getPatient().getId());

        auditService.logDoctorAction(review.getDoctor().getId(), review.getPatient().getId(),
                AuditLog.AuditAction.REVIEW_SUBMITTED,
                "Review submitted by " + review.getPhysicianDomain().getDisplayName());

        return savedReview;
    }

    /**
     * Check if all reviews are complete and generate final protocol
     */
    @Transactional
    public void checkAndGenerateFinalProtocol(UUID patientId) {
        long completedCount = reviewRepository.countCompletedReviewsForPatient(patientId);

        log.info("Patient {} has {}/{} reviews completed", patientId, completedCount, REQUIRED_REVIEWS);

        if (completedCount >= REQUIRED_REVIEWS) {
            generateFinalProtocol(patientId);
        }
    }

    /**
     * Generate the consolidated final protocol from all reviews
     */
    @Transactional
    public FinalProtocol generateFinalProtocol(UUID patientId) {
        log.info("Generating final protocol for patient: {}", patientId);

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found"));

        List<TumorBoardReview> reviews = reviewRepository.findByPatientId(patientId);

        // Consolidate all protocols
        Map<String, Object> ecsProtocol = new HashMap<>();
        Map<String, Object> dietFastingProtocol = new HashMap<>();
        Map<String, Object> mushroomProtocol = new HashMap<>();
        Map<String, Object> herbProtocol = new HashMap<>();
        Map<String, Object> drugProtocol = new HashMap<>();
        Map<String, Object> specialtyProtocol = new HashMap<>();
        StringBuilder consolidatedNotes = new StringBuilder();

        Set<String> allEcs = new HashSet<>();
        Set<String> allMushrooms = new HashSet<>();
        Set<String> allHerbs = new HashSet<>();
        Set<String> allDrugs = new HashSet<>();
        Set<String> allSpecialties = new HashSet<>();
        Map<String, Integer> dietVotes = new HashMap<>();
        Map<String, Integer> fastingVotes = new HashMap<>();

        for (TumorBoardReview review : reviews) {
            if (review.getSelectedProtocols() != null) {
                Map<String, Object> protocols = review.getSelectedProtocols();

                // Collect ECS products
                collectListItems(protocols, "ecsDefault", allEcs);
                collectListItems(protocols, "ecsOptional", allEcs);

                // Collect mushrooms
                collectListItems(protocols, "mushrooms", allMushrooms);

                // Collect herbs
                collectListItems(protocols, "herbs", allHerbs);

                // Collect drugs
                collectListItems(protocols, "repurposedDrugs", allDrugs);

                // Collect specialties
                collectListItems(protocols, "specialty", allSpecialties);

                // Vote for diet
                if (protocols.containsKey("diet") && protocols.get("diet") != null) {
                    String diet = protocols.get("diet").toString();
                    dietVotes.merge(diet, 1, Integer::sum);
                }

                // Vote for fasting
                if (protocols.containsKey("fasting") && protocols.get("fasting") != null) {
                    String fasting = protocols.get("fasting").toString();
                    fastingVotes.merge(fasting, 1, Integer::sum);
                }
            }

            // Collect notes
            if (review.getNotes() != null && !review.getNotes().isEmpty()) {
                consolidatedNotes.append("\n### ")
                        .append(review.getPhysicianDomain().getDisplayName())
                        .append(":\n")
                        .append(review.getNotes())
                        .append("\n");
            }

            if (review.getRecommendations() != null && !review.getRecommendations().isEmpty()) {
                consolidatedNotes.append("**Recommendations:** ")
                        .append(review.getRecommendations())
                        .append("\n");
            }
        }

        // Build final protocols
        ecsProtocol.put("products", new ArrayList<>(allEcs));
        mushroomProtocol.put("products", new ArrayList<>(allMushrooms));
        herbProtocol.put("products", new ArrayList<>(allHerbs));
        drugProtocol.put("products", new ArrayList<>(allDrugs));
        specialtyProtocol.put("treatments", new ArrayList<>(allSpecialties));

        // Select most voted diet and fasting
        String selectedDiet = dietVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Individualized");

        String selectedFasting = fastingVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("12h Overnight");

        dietFastingProtocol.put("diet", selectedDiet);
        dietFastingProtocol.put("fasting", selectedFasting);
        dietFastingProtocol.put("lifestyle", "No Sugar");

        // Create or update final protocol
        FinalProtocol finalProtocol = protocolRepository.findByPatientId(patientId)
                .orElse(FinalProtocol.builder().patient(patient).build());

        finalProtocol.setCancerType(patient.getCancerType());
        finalProtocol.setEcsProtocol(ecsProtocol);
        finalProtocol.setDietFastingProtocol(dietFastingProtocol);
        finalProtocol.setMushroomProtocol(mushroomProtocol);
        finalProtocol.setHerbProtocol(herbProtocol);
        finalProtocol.setDrugProtocol(drugProtocol);
        finalProtocol.setSpecialtyProtocol(specialtyProtocol);
        finalProtocol.setConsolidatedNotes(consolidatedNotes.toString());
        finalProtocol.setStatus(ProtocolStatus.PENDING_APPROVAL);
        finalProtocol.setApprovalCount(reviews.size());

        FinalProtocol saved = protocolRepository.save(finalProtocol);

        auditService.logSystemAction(patientId, AuditLog.AuditAction.FINAL_PROTOCOL_GENERATED,
                "Final protocol generated with " + reviews.size() + " doctor reviews");

        return saved;
    }

    @SuppressWarnings("unchecked")
    private void collectListItems(Map<String, Object> protocols, String key, Set<String> target) {
        if (protocols.containsKey(key) && protocols.get(key) instanceof List) {
            List<String> items = (List<String>) protocols.get(key);
            target.addAll(items);
        }
    }

    /**
     * Approve final protocol
     */
    @Transactional
    public FinalProtocol approveFinalProtocol(UUID protocolId) {
        FinalProtocol protocol = protocolRepository.findById(protocolId)
                .orElseThrow(() -> new RuntimeException("Protocol not found"));

        protocol.setStatus(ProtocolStatus.APPROVED);
        protocol.setApprovedAt(LocalDateTime.now());

        return protocolRepository.save(protocol);
    }

    /**
     * Get review status summary for a patient
     */
    public Map<String, Object> getReviewStatusForPatient(UUID patientId) {
        List<TumorBoardReview> reviews = reviewRepository.findByPatientId(patientId);

        Map<String, Object> status = new HashMap<>();
        status.put("totalRequired", REQUIRED_REVIEWS);
        status.put("completed", reviews.stream().filter(TumorBoardReview::isCompleted).count());
        status.put("pending", reviews.stream().filter(r -> r.getStatus() == ReviewStatus.PENDING).count());
        status.put("inProgress", reviews.stream().filter(r -> r.getStatus() == ReviewStatus.IN_PROGRESS).count());

        Map<String, String> domainStatus = new HashMap<>();
        for (TumorBoardReview review : reviews) {
            domainStatus.put(review.getPhysicianDomain().name(), review.getStatus().name());
        }
        status.put("domainStatus", domainStatus);

        return status;
    }

    /**
     * Get all patients awaiting tumor board review
     */
    public List<Patient> getPatientsAwaitingReview() {
        List<UUID> patientIds = reviewRepository.findPatientIdsWithPendingReviews();
        return patientRepository.findAllById(patientIds);
    }
}
