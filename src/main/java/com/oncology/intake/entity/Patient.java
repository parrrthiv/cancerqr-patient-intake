package com.oncology.intake.entity;

import com.oncology.intake.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Patient entity representing cancer patients in the system.
 * Contains demographic and clinical intake information.
 * 
 * PRIVACY NOTE: This entity contains Protected Health Information (PHI).
 * Ensure proper access controls and audit logging.
 */
@Entity
@Table(name = "patients", indexes = {
    @Index(name = "idx_whatsapp_number", columnList = "whatsapp_number", unique = true),
    @Index(name = "idx_created_at", columnList = "created_at"),
    @Index(name = "idx_patients_referring_doctor_id", columnList = "referring_doctor_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "whatsapp_number", nullable = false, unique = true, length = 20)
    private String whatsappNumber;

    // Stored AES-256-GCM encrypted via {@link EncryptedStringConverter}. Column
    // is widened to 500 chars to fit ciphertext + base64 + prefix overhead for
    // names up to ~250 plaintext chars. Legacy plaintext rows decrypt to themselves.
    @Column(name = "name", length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String name;

    @Column(name = "cancer_type", length = 100)
    private String cancerType;

    @Column(name = "age")
    private Integer age;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "pain_scale")
    private Integer painScale;

    @Column(name = "diagnosis_date")
    private LocalDate diagnosisDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_state", nullable = false)
    @Builder.Default
    private ConversationState conversationState = ConversationState.INITIAL;

    @Column(name = "intake_completed")
    @Builder.Default
    private Boolean intakeCompleted = false;

    @Column(name = "pet_scan_uploaded")
    @Builder.Default
    private Boolean petScanUploaded = false;

    @Column(name = "blood_report_uploaded")
    @Builder.Default
    private Boolean bloodReportUploaded = false;

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Report> reports = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Analysis> analyses = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_interaction_at")
    private LocalDateTime lastInteractionAt;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "consent_given")
    @Builder.Default
    private Boolean consentGiven = false;

    @Column(name = "consent_timestamp")
    private LocalDateTime consentTimestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referring_doctor_id")
    private Doctor referringDoctor;

    @Column(name = "cancer_stage", length = 50)
    private String cancerStage;

    @Column(name = "esr_value", precision = 6, scale = 2)
    private BigDecimal esrValue;

    @Column(name = "crp_value", precision = 6, scale = 2)
    private BigDecimal crpValue;

    @Column(name = "effective_pain_scale")
    private Integer effectivePainScale;

    // Helper methods
    public void addReport(Report report) {
        reports.add(report);
        report.setPatient(this);
    }

    public void addAnalysis(Analysis analysis) {
        analyses.add(analysis);
        analysis.setPatient(this);
    }

    public boolean hasAllUploads() {
        return Boolean.TRUE.equals(petScanUploaded) && Boolean.TRUE.equals(bloodReportUploaded);
    }

    public boolean hasBasicInfo() {
        return age != null && weightKg != null && painScale != null && diagnosisDate != null;
    }

    /**
     * Conversation state machine states for WhatsApp flow
     */
    public enum ConversationState {
        INITIAL,
        AWAITING_CONSENT,
        ASK_REFERRAL_CODE,
        ASK_CANCER_TYPE,
        ASK_AGE,
        ASK_WEIGHT,
        ASK_PAIN_SCALE,
        ASK_DIAGNOSIS_DATE,
        ASK_PET_SCAN,
        ASK_BLOOD_REPORT,
        PROCESSING,
        RESULT_SENT,
        COMPLETED,
        EXPIRED
    }
}
