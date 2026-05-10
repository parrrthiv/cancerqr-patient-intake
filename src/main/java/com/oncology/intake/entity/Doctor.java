package com.oncology.intake.entity;

import com.oncology.intake.security.EncryptedStringConverter;
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
/*
 * Annotation choices (deliberate):
 *   @Getter / @Setter         — JPA needs them; Lombok generates them.
 *   @ToString(exclude = ...)  — NEVER include the password hash in toString.
 *                               @Data would have, and one stray
 *                               log.info("doctor: {}", d) would leak every
 *                               BCrypt hash to the log stream.
 *   @EqualsAndHashCode(of=id) — the JPA-correct identity. Lombok's default
 *                               @Data hashes every field; that breaks Set
 *                               membership for managed entities whose
 *                               fields mutate after add().
 */
@Entity
@Table(name = "doctors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "password")
@EqualsAndHashCode(of = "id")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    // Stored as `{noop}<plaintext>` (legacy rows) or `{bcrypt}<hash>` (new rows).
    // Spring Security handles the encoding via DelegatingPasswordEncoder. NOT
    // PHI-encrypted — BCrypt is its own one-way protection.
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String fullName;

    @Column(length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String email;

    @Column(length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String phone;

    @Column(unique = true)
    private String referralCode;

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
        ADMIN("Administrator"),
        REFERRING_DOCTOR("Referring Doctor");

        private final String displayName;

        PhysicianDomain(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
