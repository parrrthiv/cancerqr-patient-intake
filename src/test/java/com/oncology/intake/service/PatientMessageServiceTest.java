package com.oncology.intake.service;

import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.PatientMessage;
import com.oncology.intake.repository.PatientMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Doctor → patient messaging: validation, persistence, best-effort WhatsApp
 * mirroring (a mirror failure must never fail the send), and read receipts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PatientMessageService")
class PatientMessageServiceTest {

    @Mock private PatientMessageRepository messageRepository;
    @Mock private WhatsAppClientService whatsAppClient;
    @Mock private AuditService auditService;

    private PatientMessageService service;

    private Doctor doctor;
    private Patient patient;

    @BeforeEach
    void setUp() {
        service = new PatientMessageService(messageRepository, whatsAppClient, auditService);
        doctor = Doctor.builder().id(UUID.randomUUID()).fullName("Dr. Meera Krishnan").build();
        patient = Patient.builder().id(UUID.randomUUID()).whatsappNumber("919876543210").build();

        when(messageRepository.save(any(PatientMessage.class))).thenAnswer(inv -> {
            PatientMessage m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        when(whatsAppClient.sendTextMessage(anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("valid message is persisted, audited, and mirrored to WhatsApp")
    void sendHappyPath() {
        PatientMessage saved = service.sendToPatient(doctor, patient, "  Please fast before the blood test.  ");

        ArgumentCaptor<PatientMessage> captor = ArgumentCaptor.forClass(PatientMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("Please fast before the blood test.", captor.getValue().getBody(), "body is trimmed");
        assertSame(doctor, captor.getValue().getDoctor());
        assertSame(patient, captor.getValue().getPatient());
        assertNull(captor.getValue().getReadAt());
        assertNotNull(saved.getId());

        verify(auditService).logDoctorAction(eq(doctor.getId()), eq(patient.getId()),
                eq(AuditAction.PATIENT_MESSAGE_SENT), anyString());

        ArgumentCaptor<String> waText = ArgumentCaptor.forClass(String.class);
        verify(whatsAppClient).sendTextMessage(eq("919876543210"), waText.capture());
        assertTrue(waText.getValue().contains("Please fast before the blood test."));
        assertTrue(waText.getValue().contains("Dr. Meera Krishnan"));
    }

    @Test
    @DisplayName("empty or oversized body is rejected before any side effect")
    void sendValidation() {
        assertThrows(IllegalArgumentException.class, () -> service.sendToPatient(doctor, patient, "   "));
        assertThrows(IllegalArgumentException.class, () -> service.sendToPatient(doctor, patient, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.sendToPatient(doctor, patient, "x".repeat(PatientMessageService.MAX_BODY_CHARS + 1)));
        verify(messageRepository, never()).save(any());
        verify(whatsAppClient, never()).sendTextMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("WhatsApp mirror failure does not fail the send (portal copy is source of truth)")
    void mirrorFailureSwallowed() {
        when(whatsAppClient.sendTextMessage(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("24h window closed")));

        PatientMessage saved = assertDoesNotThrow(
                () -> service.sendToPatient(doctor, patient, "See you at the clinic."));
        assertNotNull(saved.getId());
        verify(messageRepository).save(any(PatientMessage.class));
    }

    @Test
    @DisplayName("markAllRead audits only when something was actually unread")
    void markAllRead() {
        when(messageRepository.markAllRead(eq(patient.getId()), any(LocalDateTime.class))).thenReturn(3);
        assertEquals(3, service.markAllRead(patient.getId()));
        verify(auditService).logPatientAction(eq(patient.getId()),
                eq(AuditAction.PATIENT_MESSAGE_READ), anyString());

        reset(auditService);
        when(messageRepository.markAllRead(eq(patient.getId()), any(LocalDateTime.class))).thenReturn(0);
        assertEquals(0, service.markAllRead(patient.getId()));
        verifyNoInteractions(auditService);
    }
}
