package com.oncology.intake.repository;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Patient entity operations.
 */
@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /**
     * Find a patient by the HMAC-SHA256 hash of their normalised WhatsApp number.
     *
     * <p>The plaintext column is AES-GCM-encrypted with a fresh IV per write,
     * so direct equality queries on it never match. Lookups go through the
     * companion {@code whatsapp_number_hash} column. Callers should compute
     * the hash via {@link com.oncology.intake.security.WhatsAppNumberHasher#hash(String)}
     * before invoking this method.
     */
    Optional<Patient> findByWhatsappNumberHash(String whatsappNumberHash);

    /**
     * Existence check by HMAC-SHA256 hash. See {@link #findByWhatsappNumberHash}.
     */
    boolean existsByWhatsappNumberHash(String whatsappNumberHash);

    /**
     * Find all patients in a specific conversation state
     */
    List<Patient> findByConversationState(ConversationState state);

    /**
     * Find active patients with incomplete intake
     */
    @Query("SELECT p FROM Patient p WHERE p.isActive = true AND p.intakeCompleted = false")
    List<Patient> findActiveIncompletePatients();

    /**
     * Find patients whose last interaction was before given time (for timeout)
     */
    @Query("SELECT p FROM Patient p WHERE p.lastInteractionAt < :cutoffTime " +
           "AND p.conversationState NOT IN ('COMPLETED', 'EXPIRED', 'INITIAL')")
    List<Patient> findStaleConversations(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Find patients needing follow-up (result sent but not completed)
     */
    @Query("SELECT p FROM Patient p WHERE p.conversationState = 'RESULT_SENT' " +
           "AND p.updatedAt < :cutoffTime")
    List<Patient> findPatientsNeedingFollowUp(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Mark stale conversations as expired
     */
    @Modifying
    @Query("UPDATE Patient p SET p.conversationState = 'EXPIRED' " +
           "WHERE p.lastInteractionAt < :cutoffTime " +
           "AND p.conversationState NOT IN ('COMPLETED', 'EXPIRED', 'INITIAL')")
    int expireStaleConversations(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Count patients by conversation state
     */
    long countByConversationState(ConversationState state);

    /**
     * Find patients created within date range
     */
    List<Patient> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Find patients with completed intake but no analysis
     */
    @Query("SELECT p FROM Patient p WHERE p.intakeCompleted = true " +
           "AND NOT EXISTS (SELECT a FROM Analysis a WHERE a.patient = p)")
    List<Patient> findPatientsWithoutAnalysis();

    /**
     * Find patients referred by a specific doctor
     */
    List<Patient> findByReferringDoctorId(UUID doctorId);

    /** Paginated variant for the dashboard list view. */
    Page<Patient> findByReferringDoctorId(UUID doctorId, Pageable pageable);

    /**
     * Find patients that have at least one TumorBoardReview row.
     * This is the visibility set for the 8 tumor-board physician domains —
     * see {@link com.oncology.intake.security.PatientAccessService}.
     */
    @Query("SELECT DISTINCT p FROM Patient p " +
           "WHERE EXISTS (SELECT 1 FROM TumorBoardReview r WHERE r.patient = p)")
    List<Patient> findAllInTumorBoard();

    /** Paginated variant of {@link #findAllInTumorBoard()}. */
    @Query(value = "SELECT DISTINCT p FROM Patient p " +
                   "WHERE EXISTS (SELECT 1 FROM TumorBoardReview r WHERE r.patient = p)",
           countQuery = "SELECT COUNT(DISTINCT p) FROM Patient p " +
                        "WHERE EXISTS (SELECT 1 FROM TumorBoardReview r WHERE r.patient = p)")
    Page<Patient> findAllInTumorBoard(Pageable pageable);

    /**
     * Atomically advance a patient's conversation state, but only if it is still
     * in the expected state. Returns the number of rows updated: 1 means this
     * caller won the transition, 0 means another concurrent or re-delivered event
     * already advanced it. Used to make WhatsApp media-upload steps race-safe so
     * a single step can't ingest two files (see ConversationService).
     *
     * <p>{@code clearAutomatically + flushAutomatically}: this is a bulk UPDATE,
     * which bypasses the persistence context. Under {@code spring.jpa.open-in-view}
     * a web request holds ONE session, so a Patient loaded earlier in the request
     * keeps its STALE {@code conversationState}; a later entity-save in the same
     * request (e.g. {@code storeReport} setting the upload flag) would then write
     * the old state back, bouncing the portal intake to the previous step. Clearing
     * the context forces a fresh read after the advance. (The WhatsApp {@code @Async}
     * path runs outside open-in-view and was unaffected, but this is correct there too.)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Patient p SET p.conversationState = :next, " +
           "p.lastInteractionAt = CURRENT_TIMESTAMP " +
           "WHERE p.id = :id AND p.conversationState = :expected")
    int advanceConversationStateIfCurrent(@Param("id") UUID id,
                                          @Param("expected") ConversationState expected,
                                          @Param("next") ConversationState next);

    /**
     * Anonymize patient data (for GDPR/retention compliance)
     */
    @Modifying
    @Query("UPDATE Patient p SET p.whatsappNumber = CONCAT('ANON_', p.id), " +
           "p.age = null, p.weightKg = null, p.painScale = null, " +
           "p.diagnosisDate = null, p.isActive = false " +
           "WHERE p.id = :patientId")
    int anonymizePatient(@Param("patientId") UUID patientId);
}
