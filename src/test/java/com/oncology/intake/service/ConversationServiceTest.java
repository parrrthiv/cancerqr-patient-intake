package com.oncology.intake.service;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConversationService state machine and input validation.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private PatientIntakeService patientIntakeService;

    @Mock
    private WhatsAppClientService whatsAppClient;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private AuditService auditService;

    private ConversationService conversationService;

    @Captor
    private ArgumentCaptor<String> messageCaptor;

    private Patient testPatient;
    private final String TEST_PHONE = "1234567890";

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                patientIntakeService,
                whatsAppClient,
                analysisService,
                auditService
        );

        testPatient = Patient.builder()
                .id(UUID.randomUUID())
                .whatsappNumber(TEST_PHONE)
                .conversationState(ConversationState.INITIAL)
                .build();

        // Default mock behavior
        when(whatsAppClient.sendTextMessage(anyString(), anyString()))
                .thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("State Machine Tests")
    class StateMachineTests {

        @Test
        @DisplayName("Initial state should send welcome message")
        void initialStateShouldSendWelcome() {
            // Given
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);

            // When
            conversationService.processTextMessage(TEST_PHONE, "hi", "msg-1");

            // Then
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("Welcome"));
            verify(patientIntakeService).updateConversationState(
                    testPatient.getId(), ConversationState.AWAITING_CONSENT);
        }

        @Test
        @DisplayName("YES consent should proceed to ASK_AGE")
        void yesConsentShouldProceedToAskAge() {
            // Given
            testPatient.setConversationState(ConversationState.AWAITING_CONSENT);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);

            // When
            conversationService.processTextMessage(TEST_PHONE, "YES", "msg-1");

            // Then
            verify(patientIntakeService).recordConsent(testPatient.getId());
            verify(patientIntakeService).updateConversationState(
                    testPatient.getId(), ConversationState.ASK_AGE);
        }

        @Test
        @DisplayName("NO consent should end conversation")
        void noConsentShouldEndConversation() {
            // Given
            testPatient.setConversationState(ConversationState.AWAITING_CONSENT);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);

            // When
            conversationService.processTextMessage(TEST_PHONE, "NO", "msg-1");

            // Then
            verify(patientIntakeService, never()).recordConsent(any());
            verify(patientIntakeService).updateConversationState(
                    testPatient.getId(), ConversationState.COMPLETED);
        }
    }

    @Nested
    @DisplayName("Age Input Validation Tests")
    class AgeValidationTests {

        @BeforeEach
        void setUp() {
            testPatient.setConversationState(ConversationState.ASK_AGE);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);
        }

        @Test
        @DisplayName("Valid age should be accepted")
        void validAgeShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "45", "msg-1");

            // Then
            verify(patientIntakeService).updateAge(testPatient.getId(), 45);
            verify(patientIntakeService).updateConversationState(
                    testPatient.getId(), ConversationState.ASK_WEIGHT);
        }

        @Test
        @DisplayName("Age over 120 should be rejected")
        void ageOver120ShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "150", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateAge(any(), anyInt());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("120"));
        }

        @Test
        @DisplayName("Non-numeric age should be rejected")
        void nonNumericAgeShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "forty-five", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateAge(any(), anyInt());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("valid age"));
        }

        @Test
        @DisplayName("Negative age should be rejected")
        void negativeAgeShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "-5", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateAge(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("Weight Input Validation Tests")
    class WeightValidationTests {

        @BeforeEach
        void setUp() {
            testPatient.setConversationState(ConversationState.ASK_WEIGHT);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);
        }

        @Test
        @DisplayName("Valid integer weight should be accepted")
        void validIntegerWeightShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "70", "msg-1");

            // Then
            verify(patientIntakeService).updateWeight(testPatient.getId(), new BigDecimal("70"));
        }

        @Test
        @DisplayName("Valid decimal weight should be accepted")
        void validDecimalWeightShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "72.5", "msg-1");

            // Then
            verify(patientIntakeService).updateWeight(testPatient.getId(), new BigDecimal("72.5"));
        }

        @Test
        @DisplayName("Weight with comma decimal separator should be accepted")
        void weightWithCommaDecimalShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "72,5", "msg-1");

            // Then
            verify(patientIntakeService).updateWeight(testPatient.getId(), new BigDecimal("72.5"));
        }

        @Test
        @DisplayName("Weight over 300 should be rejected")
        void weightOver300ShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "350", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateWeight(any(), any());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("300"));
        }
    }

    @Nested
    @DisplayName("Pain Scale Input Validation Tests")
    class PainScaleValidationTests {

        @BeforeEach
        void setUp() {
            testPatient.setConversationState(ConversationState.ASK_PAIN_SCALE);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);
        }

        @Test
        @DisplayName("Valid pain scale 0 should be accepted")
        void painScale0ShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "0", "msg-1");

            // Then
            verify(patientIntakeService).updatePainScale(testPatient.getId(), 0);
        }

        @Test
        @DisplayName("Valid pain scale 10 should be accepted")
        void painScale10ShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "10", "msg-1");

            // Then
            verify(patientIntakeService).updatePainScale(testPatient.getId(), 10);
        }

        @Test
        @DisplayName("Pain scale over 10 should be rejected")
        void painScaleOver10ShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "11", "msg-1");

            // Then
            verify(patientIntakeService, never()).updatePainScale(any(), anyInt());
        }

        @Test
        @DisplayName("Interactive pain selection should be accepted")
        void interactivePainSelectionShouldBeAccepted() {
            // When
            conversationService.processInteractiveResponse(TEST_PHONE, "pain_7", "7");

            // Then
            verify(patientIntakeService).updatePainScale(testPatient.getId(), 7);
        }
    }

    @Nested
    @DisplayName("Diagnosis Date Input Validation Tests")
    class DiagnosisDateValidationTests {

        @BeforeEach
        void setUp() {
            testPatient.setConversationState(ConversationState.ASK_DIAGNOSIS_DATE);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);
        }

        @Test
        @DisplayName("Valid date format should be accepted")
        void validDateFormatShouldBeAccepted() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "2024-01-15", "msg-1");

            // Then
            verify(patientIntakeService).updateDiagnosisDate(
                    testPatient.getId(), LocalDate.of(2024, 1, 15));
        }

        @Test
        @DisplayName("Future date should be rejected")
        void futureDateShouldBeRejected() {
            // Given
            String futureDate = LocalDate.now().plusDays(30).toString();

            // When
            conversationService.processTextMessage(TEST_PHONE, futureDate, "msg-1");

            // Then
            verify(patientIntakeService, never()).updateDiagnosisDate(any(), any());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("future"));
        }

        @Test
        @DisplayName("Invalid date format should be rejected")
        void invalidDateFormatShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "15-01-2024", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateDiagnosisDate(any(), any());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("YYYY-MM-DD"));
        }

        @Test
        @DisplayName("Invalid date values should be rejected")
        void invalidDateValuesShouldBeRejected() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "2024-13-45", "msg-1");

            // Then
            verify(patientIntakeService, never()).updateDiagnosisDate(any(), any());
        }
    }

    @Nested
    @DisplayName("Completed State Tests")
    class CompletedStateTests {

        @BeforeEach
        void setUp() {
            testPatient.setConversationState(ConversationState.COMPLETED);
            when(patientIntakeService.findOrCreatePatient(TEST_PHONE))
                    .thenReturn(testPatient);
            when(patientIntakeService.getPatient(testPatient.getId()))
                    .thenReturn(testPatient);
        }

        @Test
        @DisplayName("START command should restart conversation")
        void startCommandShouldRestart() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "START", "msg-1");

            // Then
            verify(patientIntakeService).resetPatientIntake(testPatient.getId());
        }

        @Test
        @DisplayName("RESTART command should restart conversation")
        void restartCommandShouldRestart() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "RESTART", "msg-1");

            // Then
            verify(patientIntakeService).resetPatientIntake(testPatient.getId());
        }

        @Test
        @DisplayName("Other messages should show completion info")
        void otherMessagesShouldShowCompletionInfo() {
            // When
            conversationService.processTextMessage(TEST_PHONE, "hello", "msg-1");

            // Then
            verify(patientIntakeService, never()).resetPatientIntake(any());
            verify(whatsAppClient).sendTextMessage(eq(TEST_PHONE), messageCaptor.capture());
            assertTrue(messageCaptor.getValue().contains("completed"));
        }
    }
}
