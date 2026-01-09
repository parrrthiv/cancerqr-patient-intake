package com.oncology.intake.repository;

import com.oncology.intake.entity.AuditLog;
import com.oncology.intake.entity.AuditLog.AuditAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for AuditLog entity operations.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Find audit logs for a patient
     */
    List<AuditLog> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /**
     * Find audit logs by action type
     */
    List<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action);

    /**
     * Find audit logs within date range
     */
    List<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime start, LocalDateTime end);

    /**
     * Find failed actions
     */
    List<AuditLog> findBySuccessFalseOrderByCreatedAtDesc();

    /**
     * Find audit logs by actor
     */
    List<AuditLog> findByActorIdOrderByCreatedAtDesc(String actorId);

    /**
     * Count actions by type within date range
     */
    long countByActionAndCreatedAtBetween(
            AuditAction action, LocalDateTime start, LocalDateTime end);
}
