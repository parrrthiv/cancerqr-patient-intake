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
 * Tumor Board Review entity.
 * Tracks each doctor's review of a patient case.
 */
@Entity
@Table(name = "tumor_board_reviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TumorBoardReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = true)  // Nullable - assigned when doctor picks up case
    private Doctor doctor;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Doctor.PhysicianDomain physicianDomain;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    // Selected protocols (stored as JSON) - H2 compatible
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "text")
    private Map<String, Object> selectedProtocols;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "TEXT")
    private String recommendations;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    public enum ReviewStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        REQUIRES_DISCUSSION
    }

    public boolean isCompleted() {
        return status == ReviewStatus.COMPLETED;
    }
}
