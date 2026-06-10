package com.oncology.intake.repository;

import com.oncology.intake.entity.PatientMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for doctor → patient messages.
 */
@Repository
public interface PatientMessageRepository extends JpaRepository<PatientMessage, UUID> {

    /**
     * All messages for a patient, newest first, with the sending doctor
     * JOIN-FETCHed so templates can render the sender name without an N+1
     * (or a LazyInitializationException outside the session).
     */
    @Query("SELECT m FROM PatientMessage m LEFT JOIN FETCH m.doctor " +
           "WHERE m.patient.id = :patientId ORDER BY m.createdAt DESC")
    List<PatientMessage> findByPatientIdWithDoctor(@Param("patientId") UUID patientId);

    long countByPatientIdAndReadAtIsNull(UUID patientId);

    /** Mark every unread message for this patient as read. Returns rows touched. */
    @Modifying
    @Query("UPDATE PatientMessage m SET m.readAt = :now " +
           "WHERE m.patient.id = :patientId AND m.readAt IS NULL")
    int markAllRead(@Param("patientId") UUID patientId, @Param("now") LocalDateTime now);
}
