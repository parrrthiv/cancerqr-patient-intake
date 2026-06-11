package com.oncology.intake.entity;

import com.oncology.intake.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message from the care team (doctor) to a patient.
 *
 * <p>Shown in the patient portal; additionally mirrored to the patient's
 * WhatsApp number on a best-effort basis (see {@code PatientMessageService}).
 *
 * <p>PRIVACY: the body is clinical content about a named patient — PHI — so it
 * is AES-GCM encrypted at rest like every other PHI column. Plaintext length
 * is capped at {@code PatientMessageService.MAX_BODY_CHARS}; the column is
 * sized 4000 to absorb ciphertext + base64 + key-version prefix overhead.
 * The doctor's name is NOT denormalised here — render via the {@code doctor}
 * association so it stays under the existing Doctor column encryption.
 */
@Entity
@Table(name = "patient_messages", indexes = {
    @Index(name = "idx_patient_messages_patient_id", columnList = "patient_id"),
    @Index(name = "idx_patient_messages_unread", columnList = "patient_id, read_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PatientMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** Sender. Nullable so system notices remain possible later. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @Column(name = "body", nullable = false, length = 4000)
    @Convert(converter = EncryptedStringConverter.class)
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Set when the patient first opens their messages page. Null = unread. */
    @Column(name = "read_at")
    private LocalDateTime readAt;
}
