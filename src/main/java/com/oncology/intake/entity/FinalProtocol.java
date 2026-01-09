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
 * Final Treatment Protocol entity.
 * Consolidated protocol after all Tumor Board reviews are complete.
 */
@Entity
@Table(name = "final_protocols")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalProtocol {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false, unique = true)
    private Patient patient;

    @Column(nullable = false)
    private String cancerType;

    // Consolidated ECS Products
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> ecsProtocol;

    // Consolidated Diet & Fasting
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> dietFastingProtocol;

    // Consolidated Mushrooms
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> mushroomProtocol;

    // Consolidated Herbs
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> herbProtocol;

    // Repurposed Drugs (if any)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> drugProtocol;

    // Specialty Treatments
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "clob")
    private Map<String, Object> specialtyProtocol;

    // All doctor notes consolidated
    @Column(columnDefinition = "TEXT")
    private String consolidatedNotes;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ProtocolStatus status = ProtocolStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Integer approvalCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean sentToPatient = false;

    private LocalDateTime sentAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime approvedAt;

    public enum ProtocolStatus {
        DRAFT,
        PENDING_APPROVAL,
        APPROVED,
        SENT,
        REJECTED
    }
}
