package com.oncology.intake.repository;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
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
     * Find patient by WhatsApp number
     */
    Optional<Patient> findByWhatsappNumber(String whatsappNumber);

    /**
     * Check if patient exists by WhatsApp number
     */
    boolean existsByWhatsappNumber(String whatsappNumber);

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
