package com.oncology.intake.entity;

import com.oncology.intake.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Login account for the patient-facing portal ({@code /portal/**}).
 *
 * <p>One account per {@link Patient}. The login identifier is the patient's
 * phone number — stored AES-GCM encrypted with a deterministic HMAC-SHA256
 * companion hash for lookups, exactly mirroring the
 * {@code Patient.whatsappNumber / whatsappNumberHash} pattern. The hash is set
 * explicitly by the service layer (single write path), not via a listener.
 *
 * <p><strong>Account-takeover guard:</strong> if a patient record already
 * exists for the phone number (i.e. the person previously talked to the
 * WhatsApp bot, so the record may contain PHI), the account is created with
 * {@code enabled = false} and a one-time code is sent to that WhatsApp number.
 * Only after the code is confirmed does the account become enabled — otherwise
 * anyone who knows a victim's phone number could register and read their
 * intake data. Brand-new numbers register directly (there is nothing to take
 * over yet); they remain {@code phoneVerified = false}, which is recorded for
 * a future SMS-OTP hardening pass before go-live.
 *
 * <p>{@code password} is BCrypt via the DelegatingPasswordEncoder — never
 * PHI-encrypted (one-way hash is its own protection). {@code otpHash} is
 * SHA-256 of the 6-digit code; the plaintext code is never persisted.
 */
@Entity
@Table(name = "patient_accounts", indexes = {
    @Index(name = "idx_patient_accounts_phone_hash", columnList = "phone_hash", unique = true),
    @Index(name = "idx_patient_accounts_patient_id", columnList = "patient_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"password", "otpHash", "phone"})
@EqualsAndHashCode(of = "id")
public class PatientAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * FK to {@code patients.id}. Mapped as a plain UUID (no association) so the
     * security principal can be built without touching a lazy proxy, and the
     * portal always re-loads the Patient through the service layer.
     */
    @Column(name = "patient_id", nullable = false, unique = true)
    private UUID patientId;

    /** HMAC-SHA256 of the normalised phone number — the login lookup key. */
    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    /** Normalised phone number, AES-GCM encrypted at rest. */
    @Column(name = "phone", nullable = false, length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String phone;

    /** {@code {bcrypt}...} via DelegatingPasswordEncoder. */
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "display_name", length = 500)
    @Convert(converter = EncryptedStringConverter.class)
    private String displayName;

    /** False while a WhatsApp-ownership verification is pending. */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** True only after the number was proven via the WhatsApp one-time code. */
    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private Boolean phoneVerified = false;

    /** SHA-256 hex of the pending 6-digit verification code (null when none). */
    @Column(name = "otp_hash", length = 64)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private LocalDateTime otpExpiresAt;

    /** Failed verification attempts for the current code (max enforced in service). */
    @Column(name = "otp_attempts", nullable = false)
    @Builder.Default
    private Integer otpAttempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
