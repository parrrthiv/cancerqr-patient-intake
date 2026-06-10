package com.oncology.intake.service;

import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.PatientMessage;
import com.oncology.intake.repository.PatientMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Doctor → patient messaging.
 *
 * <p>Messages are persisted (encrypted body) and shown in the patient portal;
 * additionally each message is mirrored to the patient's WhatsApp number on a
 * best-effort basis so WhatsApp-first patients see it without ever opening the
 * portal. A failed mirror (number not on WhatsApp, closed 24h window, expired
 * token) never fails the send — the portal copy is the source of truth.
 *
 * <p>Authorization is the caller's job: the dashboard endpoint must pass a
 * doctor that {@code PatientAccessService.canViewPatient} already cleared, and
 * portal reads must use the patient id from the session principal only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientMessageService {

    /** Plaintext cap; the encrypted column is sized for this (see V10). */
    public static final int MAX_BODY_CHARS = 1000;

    private final PatientMessageRepository messageRepository;
    private final WhatsAppClientService whatsAppClient;
    private final AuditService auditService;

    /**
     * Persist a message from {@code doctor} to {@code patient} and mirror it to
     * WhatsApp best-effort. Throws {@link IllegalArgumentException} with a
     * user-displayable message on validation failure.
     */
    public PatientMessage sendToPatient(Doctor doctor, Patient patient, String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty.");
        }
        if (trimmed.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException(
                    "Message is too long (max " + MAX_BODY_CHARS + " characters).");
        }

        PatientMessage message = messageRepository.save(PatientMessage.builder()
                .patient(patient)
                .doctor(doctor)
                .body(trimmed)
                .build());

        // Log IDs only — the body is PHI.
        auditService.logDoctorAction(doctor.getId(), patient.getId(),
                AuditAction.PATIENT_MESSAGE_SENT,
                "Doctor sent a message to the patient (" + trimmed.length() + " chars)");

        mirrorToWhatsApp(patient, doctor, trimmed);
        return message;
    }

    /** Best-effort WhatsApp copy; failures are logged, never propagated. */
    private void mirrorToWhatsApp(Patient patient, Doctor doctor, String body) {
        try {
            String text = "💬 *Message from your care team*\n\n"
                    + body + "\n\n— " + doctor.getFullName()
                    + "\n\nYou can also read your messages in the patient portal.";
            whatsAppClient.sendTextMessage(patient.getWhatsappNumber(), text).block();
        } catch (Exception e) {
            log.warn("WhatsApp mirror failed for message to patient {}: {}",
                    patient.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<PatientMessage> messagesForPatient(UUID patientId) {
        return messageRepository.findByPatientIdWithDoctor(patientId);
    }

    public long unreadCount(UUID patientId) {
        return messageRepository.countByPatientIdAndReadAtIsNull(patientId);
    }

    /** Mark everything read for this patient (called when they open the inbox). */
    @Transactional
    public int markAllRead(UUID patientId) {
        int updated = messageRepository.markAllRead(patientId, LocalDateTime.now());
        if (updated > 0) {
            auditService.logPatientAction(patientId, AuditAction.PATIENT_MESSAGE_READ,
                    "Patient read " + updated + " message(s) in the portal");
        }
        return updated;
    }
}
