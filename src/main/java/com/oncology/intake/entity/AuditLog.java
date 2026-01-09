package com.oncology.intake.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Audit log entity for tracking all significant actions in the system.
 * Essential for compliance, debugging, and security monitoring.
 * 
 * PRIVACY NOTE: Audit logs should not contain actual PHI,
 * only references (IDs) and action metadata.
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_patient_id", columnList = "patient_id"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_created_at", columnList = "created_at"),
    @Index(name = "idx_audit_actor", columnList = "actor_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id")
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "action_detail", length = 500)
    private String actionDetail;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "clob")
    private Map<String, Object> metadataJson;

    @Column(name = "success")
    @Builder.Default
    private Boolean success = true;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Types of auditable actions
     */
    public enum AuditAction {
        // Patient lifecycle
        PATIENT_CREATED,
        PATIENT_UPDATED,
        PATIENT_DELETED,
        PATIENT_ANONYMIZED,
        
        // Conversation flow
        CONVERSATION_STARTED,
        CONVERSATION_STATE_CHANGED,
        CONSENT_GIVEN,
        INTAKE_COMPLETED,
        
        // Report handling
        REPORT_UPLOADED,
        REPORT_DOWNLOADED,
        REPORT_DELETED,
        
        // Analysis
        ANALYSIS_GENERATED,
        ANALYSIS_SENT,
        ANALYSIS_REVIEWED,
        
        // Tumor Board
        TUMOR_BOARD_CREATED,
        REVIEW_SUBMITTED,
        FINAL_PROTOCOL_GENERATED,
        PROTOCOL_APPROVED,
        PROTOCOL_SENT,
        
        // WhatsApp events
        WHATSAPP_MESSAGE_RECEIVED,
        WHATSAPP_MESSAGE_SENT,
        WHATSAPP_MEDIA_RECEIVED,
        WHATSAPP_WEBHOOK_RECEIVED,
        
        // Security events
        AUTHENTICATION_SUCCESS,
        AUTHENTICATION_FAILED,
        AUTHORIZATION_DENIED,
        
        // Admin actions
        ADMIN_DATA_ACCESS,
        ADMIN_CONFIG_CHANGE,
        
        // System events
        SYSTEM_ERROR,
        DATA_EXPORT,
        DATA_RETENTION_CLEANUP
    }

    /**
     * Type of actor performing the action
     */
    public enum ActorType {
        PATIENT,
        SYSTEM,
        ADMIN,
        PHYSICIAN,
        API_CLIENT,
        SCHEDULER
    }
}
