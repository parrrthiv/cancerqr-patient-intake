package com.oncology.intake.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Doctor/User entity for Tumor Board members.
 * Represents one of the 8 physician domains.
 */
@Entity
@Table(name = "doctors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PhysicianDomain domain;

    @Column
    private String email;

    @Column
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * The 8 Physician Domains in the Tumor Board
     */
    public enum PhysicianDomain {
        MEDICAL_ONCOLOGY("Medical Oncology"),
        SURGICAL_ONCOLOGY("Surgical Oncology"),
        RADIATION_ONCOLOGY("Radiation Oncology"),
        PRECISION_ONCOLOGY("Precision Oncology"),
        PALLIATIVE_CARE("Palliative Care"),
        AYURVEDA_INTEGRATIVE("Ayurveda/Integrative"),
        FUNCTIONAL_MEDICINE("Functional Medicine"),
        DIETICIAN_NUTRITION("Dietician/Nutrition"),
        ADMIN("Administrator");

        private final String displayName;

        PhysicianDomain(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
