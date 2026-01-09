package com.oncology.intake.service;

import com.oncology.intake.entity.AuditLog;
import com.oncology.intake.entity.AuditLog.ActorType;
import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Service for creating audit log entries.
 * All audit operations are asynchronous to not block main operations.
 * 
 * PRIVACY NOTE: Audit logs should contain only identifiers,
 * not actual PHI data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Log a patient-related action
     */
    @Async
    public void logPatientAction(UUID patientId, AuditAction action, String detail) {
        logAction(patientId, action, detail, null, ActorType.PATIENT, true, null);
    }

    /**
     * Log a system action
     */
    @Async
    public void logSystemAction(UUID patientId, AuditAction action, String detail) {
        logAction(patientId, action, detail, "SYSTEM", ActorType.SYSTEM, true, null);
    }

    /**
     * Log an action with metadata
     */
    @Async
    public void logActionWithMetadata(UUID patientId, AuditAction action, 
                                       String detail, Map<String, Object> metadata) {
        logAction(patientId, action, detail, "SYSTEM", ActorType.SYSTEM, true, metadata);
    }

    /**
     * Log a failed action
     */
    @Async
    public void logFailedAction(UUID patientId, AuditAction action, 
                                 String detail, String errorMessage) {
        AuditLog auditLog = AuditLog.builder()
                .patientId(patientId)
                .action(action)
                .actionDetail(detail)
                .actorType(ActorType.SYSTEM)
                .success(false)
                .errorMessage(errorMessage)
                .build();

        try {
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            // Don't let audit logging failures affect main operations
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }

    /**
     * Log a WhatsApp event
     */
    @Async
    public void logWhatsAppEvent(UUID patientId, AuditAction action, 
                                  String detail, String ipAddress) {
        AuditLog auditLog = AuditLog.builder()
                .patientId(patientId)
                .action(action)
                .actionDetail(detail)
                .actorId(patientId != null ? patientId.toString() : null)
                .actorType(ActorType.PATIENT)
                .ipAddress(ipAddress)
                .success(true)
                .build();

        try {
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save WhatsApp audit log: {}", e.getMessage());
        }
    }

    /**
     * Log an admin action
     */
    @Async
    public void logAdminAction(UUID patientId, AuditAction action, 
                                String detail, String adminId) {
        logAction(patientId, action, detail, adminId, ActorType.ADMIN, true, null);
    }

    /**
     * Log a doctor/physician action
     */
    @Async
    public void logDoctorAction(UUID doctorId, UUID patientId, AuditAction action, String detail) {
        logAction(patientId, action, detail, doctorId.toString(), ActorType.PHYSICIAN, true, null);
    }

    /**
     * Log AI verification result
     */
    @Async
    public void logVerification(Object input, boolean approved, double score, Object issues) {
        Map<String, Object> metadata = Map.of(
            "approved", approved,
            "confidence_score", score,
            "issues_count", issues != null ? ((java.util.List<?>) issues).size() : 0
        );
        logAction(null, AuditAction.ANALYSIS_REVIEWED, 
                  "AI Verification: " + (approved ? "APPROVED" : "FLAGGED"), 
                  "AI_VERIFIER", ActorType.SYSTEM, approved, metadata);
    }

    /**
     * Core logging method
     */
    private void logAction(UUID patientId, AuditAction action, String detail,
                           String actorId, ActorType actorType, 
                           boolean success, Map<String, Object> metadata) {
        AuditLog auditLog = AuditLog.builder()
                .patientId(patientId)
                .action(action)
                .actionDetail(detail)
                .actorId(actorId)
                .actorType(actorType)
                .success(success)
                .metadataJson(metadata)
                .build();

        try {
            auditLogRepository.save(auditLog);
            log.debug("Audit log created: {} for patient {}", action, patientId);
        } catch (Exception e) {
            // Don't let audit logging failures affect main operations
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }
}
