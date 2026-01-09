package com.oncology.intake.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Report entity for storing medical report metadata.
 * Actual files are stored in object storage (S3/MinIO) or filesystem.
 * This entity maintains references and metadata only.
 */
@Entity
@Table(name = "reports", indexes = {
    @Index(name = "idx_patient_id", columnList = "patient_id"),
    @Index(name = "idx_report_type", columnList = "report_type"),
    @Index(name = "idx_uploaded_at", columnList = "uploaded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false)
    private ReportType reportType;

    @Column(name = "storage_location", nullable = false, length = 500)
    private String storageLocation;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "checksum", length = 64)
    private String checksum;

    @Column(name = "whatsapp_media_id", length = 100)
    private String whatsappMediaId;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "processed")
    @Builder.Default
    private Boolean processed = false;

    @Column(name = "processing_notes", columnDefinition = "TEXT")
    private String processingNotes;

    /**
     * Types of medical reports supported
     */
    public enum ReportType {
        PET_SCAN("PET Scan Report", "Positron Emission Tomography scan"),
        BLOOD_REPORT("Blood Report", "Complete blood count and related tests"),
        CT_SCAN("CT Scan", "Computed Tomography scan"),
        MRI("MRI Report", "Magnetic Resonance Imaging"),
        BIOPSY("Biopsy Report", "Tissue biopsy results"),
        OTHER("Other Report", "Other medical documents");

        private final String displayName;
        private final String description;

        ReportType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }
}
