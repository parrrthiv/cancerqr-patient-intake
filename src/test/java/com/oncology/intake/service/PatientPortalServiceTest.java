package com.oncology.intake.service;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.PatientAccount;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.repository.*;
import com.oncology.intake.security.WhatsAppNumberHasher;
import com.oncology.intake.service.PatientPortalService.PortalException;
import com.oncology.intake.service.PatientPortalService.RegistrationOutcome;
import com.oncology.intake.service.PatientPortalService.VerifyOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the patient portal: registration + the WhatsApp-OTP
 * account-takeover guard, the web intake steps (same state machine as the
 * bot), and upload claim semantics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PatientPortalService")
class PatientPortalServiceTest {

    private static final String NEW_PHONE = "+91 99999 11111";
    private static final String NEW_PHONE_NORMALISED = "919999911111";
    private static final Pattern CODE_IN_MESSAGE = Pattern.compile("\\*(\\d{6})\\*");

    @Mock private PatientAccountRepository accountRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private FinalProtocolRepository protocolRepository;
    @Mock private PatientMessageRepository messageRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private PatientIntakeService patientIntakeService;
    @Mock private AnalysisService analysisService;
    @Mock private TumorBoardService tumorBoardService;
    @Mock private WhatsAppClientService whatsAppClient;
    @Mock private WhatsAppNumberHasher hasher;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private CancerQRProtocolConfig protocolConfig;

    private PatientPortalService service;

    @BeforeEach
    void setUp() {
        service = new PatientPortalService(
                accountRepository, patientRepository, reportRepository, protocolRepository,
                messageRepository, doctorRepository, patientIntakeService, analysisService,
                tumorBoardService, whatsAppClient, hasher, passwordEncoder, auditService,
                protocolConfig);

        // Deterministic hash: "h:" + normalised digits (mirrors hasher semantics).
        when(hasher.hash(anyString())).thenAnswer(inv ->
                "h:" + WhatsAppNumberHasher.normalise(inv.getArgument(0)));
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "{bcrypt}" + inv.getArgument(0));
        when(accountRepository.save(any(PatientAccount.class))).thenAnswer(inv -> {
            PatientAccount a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(whatsAppClient.sendTextMessage(anyString(), anyString())).thenReturn(Mono.empty());
    }

    // ── Registration ────────────────────────────────────────────────────

    @Test
    @DisplayName("new number → patient + ENABLED account, no OTP")
    void registerNewNumber() {
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByWhatsappNumberHash(anyString())).thenReturn(Optional.empty());

        RegistrationOutcome outcome = service.register(NEW_PHONE, "Asha Patel", "secret-pass");

        assertEquals(RegistrationOutcome.CREATED, outcome);

        ArgumentCaptor<Patient> patientCaptor = ArgumentCaptor.forClass(Patient.class);
        verify(patientRepository).save(patientCaptor.capture());
        assertEquals(NEW_PHONE_NORMALISED, patientCaptor.getValue().getWhatsappNumber());
        assertEquals(ConversationState.INITIAL, patientCaptor.getValue().getConversationState());

        ArgumentCaptor<PatientAccount> accCaptor = ArgumentCaptor.forClass(PatientAccount.class);
        verify(accountRepository).save(accCaptor.capture());
        PatientAccount acc = accCaptor.getValue();
        assertTrue(acc.getEnabled());
        assertFalse(acc.getPhoneVerified());
        assertNull(acc.getOtpHash());
        assertTrue(acc.getPassword().startsWith("{bcrypt}"));

        verify(whatsAppClient, never()).sendTextMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("number with an existing patient record → DISABLED account + WhatsApp code")
    void registerExistingPatientRequiresOtp() {
        Patient existing = Patient.builder().id(UUID.randomUUID())
                .whatsappNumber(NEW_PHONE_NORMALISED).build();
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByWhatsappNumberHash(anyString())).thenReturn(Optional.of(existing));

        RegistrationOutcome outcome = service.register(NEW_PHONE, "Asha Patel", "secret-pass");

        assertEquals(RegistrationOutcome.OTP_SENT, outcome);

        ArgumentCaptor<PatientAccount> accCaptor = ArgumentCaptor.forClass(PatientAccount.class);
        verify(accountRepository).save(accCaptor.capture());
        PatientAccount acc = accCaptor.getValue();
        assertFalse(acc.getEnabled(), "account must not be usable before verification");
        assertEquals(existing.getId(), acc.getPatientId());
        assertNotNull(acc.getOtpHash());
        assertNotNull(acc.getOtpExpiresAt());

        // The code goes to the EXISTING record's number over WhatsApp.
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsAppClient).sendTextMessage(eq(NEW_PHONE_NORMALISED), msgCaptor.capture());
        assertTrue(CODE_IN_MESSAGE.matcher(msgCaptor.getValue()).find(),
                "OTP message must contain a 6-digit code");
        // No new patient row.
        verify(patientRepository, never()).save(any());
    }

    @Test
    @DisplayName("enabled account already exists → rejected")
    void registerDuplicateRejected() {
        PatientAccount enabled = PatientAccount.builder()
                .id(UUID.randomUUID()).patientId(UUID.randomUUID())
                .enabled(true).build();
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.of(enabled));

        assertThrows(PortalException.class,
                () -> service.register(NEW_PHONE, "Asha", "secret-pass"));
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("WhatsApp send failure aborts registration (no half-created pending account)")
    void registerOtpSendFailureAborts() {
        Patient existing = Patient.builder().id(UUID.randomUUID()).build();
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByWhatsappNumberHash(anyString())).thenReturn(Optional.of(existing));
        when(whatsAppClient.sendTextMessage(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("token expired")));

        assertThrows(PortalException.class,
                () -> service.register(NEW_PHONE, "Asha", "secret-pass"));
        // The throw happens before save — and in production the @Transactional
        // boundary would roll back anything saved anyway.
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("weak inputs rejected")
    void registerValidation() {
        assertThrows(PortalException.class, () -> service.register(NEW_PHONE, "", "secret-pass"));
        assertThrows(PortalException.class, () -> service.register("abc", "Asha", "secret-pass"));
        assertThrows(PortalException.class, () -> service.register(NEW_PHONE, "Asha", "short"));
    }

    // ── OTP verification ────────────────────────────────────────────────

    /** Register against an existing record and pull the code out of the mocked send. */
    private String registerAndCaptureCode(PatientAccount[] savedAccount) {
        Patient existing = Patient.builder().id(UUID.randomUUID()).build();
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.empty());
        when(patientRepository.findByWhatsappNumberHash(anyString())).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(PatientAccount.class))).thenAnswer(inv -> {
            PatientAccount a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            savedAccount[0] = a;
            return a;
        });

        service.register(NEW_PHONE, "Asha", "secret-pass");

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(whatsAppClient).sendTextMessage(anyString(), msgCaptor.capture());
        Matcher m = CODE_IN_MESSAGE.matcher(msgCaptor.getValue());
        assertTrue(m.find());

        // Subsequent lookups (the verify step) find the pending account.
        when(accountRepository.findByPhoneHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(savedAccount[0]));
        return m.group(1);
    }

    @Test
    @DisplayName("correct code enables + verifies the account")
    void verifyCorrectCode() {
        PatientAccount[] saved = new PatientAccount[1];
        String code = registerAndCaptureCode(saved);

        assertEquals(VerifyOutcome.VERIFIED, service.verifyOtp(NEW_PHONE, code));
        assertTrue(saved[0].getEnabled());
        assertTrue(saved[0].getPhoneVerified());
        assertNull(saved[0].getOtpHash(), "code must be cleared after use");
    }

    @Test
    @DisplayName("wrong code increments attempts and locks after 5")
    void verifyWrongCodeLocks() {
        PatientAccount[] saved = new PatientAccount[1];
        String code = registerAndCaptureCode(saved);
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < 5; i++) {
            assertEquals(VerifyOutcome.INVALID_CODE, service.verifyOtp(NEW_PHONE, wrong));
        }
        assertEquals(5, saved[0].getOtpAttempts());
        // Even the RIGHT code is refused once locked.
        assertEquals(VerifyOutcome.TOO_MANY_ATTEMPTS, service.verifyOtp(NEW_PHONE, code));
        assertFalse(saved[0].getEnabled());
    }

    @Test
    @DisplayName("expired code is refused")
    void verifyExpired() {
        PatientAccount[] saved = new PatientAccount[1];
        String code = registerAndCaptureCode(saved);
        saved[0].setOtpExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));

        assertEquals(VerifyOutcome.EXPIRED, service.verifyOtp(NEW_PHONE, code));
        assertFalse(saved[0].getEnabled());
    }

    @Test
    @DisplayName("no pending verification → NOT_PENDING")
    void verifyNotPending() {
        when(accountRepository.findByPhoneHash(anyString())).thenReturn(Optional.empty());
        assertEquals(VerifyOutcome.NOT_PENDING, service.verifyOtp(NEW_PHONE, "123456"));
    }

    // ── Web intake ──────────────────────────────────────────────────────

    private Patient patientInState(ConversationState state) {
        Patient p = Patient.builder().id(UUID.randomUUID())
                .whatsappNumber(NEW_PHONE_NORMALISED)
                .conversationState(state).build();
        when(patientIntakeService.getPatient(p.getId())).thenReturn(p);
        return p;
    }

    @Test
    @DisplayName("basics: valid submission updates fields and advances to ASK_PET_SCAN")
    void basicsHappyPath() {
        Patient p = patientInState(ConversationState.ASK_CANCER_TYPE);
        CancerQRProtocolConfig.CancerProtocol proto = new CancerQRProtocolConfig.CancerProtocol();
        when(protocolConfig.getCancerProtocols()).thenReturn(Map.of("BREAST_CANCER", proto));

        service.submitBasics(p.getId(), "BREAST_CANCER", 52, new BigDecimal("70.5"),
                6, "2024-03-15", null);

        verify(patientIntakeService).updateCancerType(p.getId(), "BREAST_CANCER");
        verify(patientIntakeService).updateAge(p.getId(), 52);
        verify(patientIntakeService).updateWeight(p.getId(), new BigDecimal("70.5"));
        verify(patientIntakeService).updatePainScale(p.getId(), 6);
        verify(patientIntakeService).updateConversationState(p.getId(), ConversationState.ASK_PET_SCAN);
    }

    @Test
    @DisplayName("basics: unknown cancer type / bad bounds rejected")
    void basicsValidation() {
        Patient p = patientInState(ConversationState.ASK_CANCER_TYPE);
        when(protocolConfig.getCancerProtocols())
                .thenReturn(Map.of("BREAST_CANCER", new CancerQRProtocolConfig.CancerProtocol()));

        assertThrows(PortalException.class, () -> service.submitBasics(
                p.getId(), "NOT_A_TYPE", 52, new BigDecimal("70"), 6, "2024-03-15", null));
        assertThrows(PortalException.class, () -> service.submitBasics(
                p.getId(), "BREAST_CANCER", 200, new BigDecimal("70"), 6, "2024-03-15", null));
        assertThrows(PortalException.class, () -> service.submitBasics(
                p.getId(), "BREAST_CANCER", 52, new BigDecimal("70"), 6, "3024-03-15", null));
        verify(patientIntakeService, never()).updateConversationState(any(), any());
    }

    @Test
    @DisplayName("basics: refused outside the form states")
    void basicsWrongState() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        assertThrows(PortalException.class, () -> service.submitBasics(
                p.getId(), "BREAST_CANCER", 52, new BigDecimal("70"), 6, "2024-03-15", null));
    }

    @Test
    @DisplayName("upload: losing the state claim stores nothing (double-submit guard)")
    void uploadClaimLost() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        when(patientIntakeService.advanceStateIfCurrent(
                p.getId(), ConversationState.ASK_PET_SCAN, ConversationState.ASK_BLOOD_REPORT))
                .thenReturn(false);

        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});

        assertThrows(PortalException.class, () -> service.handleUpload(p.getId(), file));
        verify(patientIntakeService, never())
                .storeReport(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("upload: blood report completes intake (analysis + tumor board + RESULT_SENT)")
    void uploadBloodCompletesIntake() {
        Patient p = patientInState(ConversationState.ASK_BLOOD_REPORT);
        when(patientIntakeService.advanceStateIfCurrent(
                p.getId(), ConversationState.ASK_BLOOD_REPORT, ConversationState.PROCESSING))
                .thenReturn(true);
        when(analysisService.getLatestAnalysis(p.getId())).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile(
                "file", "blood.pdf", "application/pdf", "%PDF-1.4 data".getBytes());

        ReportType stored = service.handleUpload(p.getId(), file);

        assertEquals(ReportType.BLOOD_REPORT, stored);
        verify(patientIntakeService).storeReport(eq(p.getId()), eq(ReportType.BLOOD_REPORT),
                any(), anyString(), eq("application/pdf"), isNull());
        verify(patientIntakeService).markIntakeCompleted(p.getId());
        verify(analysisService).generateAnalysis(p.getId());
        verify(tumorBoardService).createReviewTasksForPatient(p.getId());
        verify(patientIntakeService).updateConversationState(p.getId(), ConversationState.RESULT_SENT);
    }

    @Test
    @DisplayName("upload: storage failure releases the claimed step for retry")
    void uploadFailureReleasesClaim() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        when(patientIntakeService.advanceStateIfCurrent(
                p.getId(), ConversationState.ASK_PET_SCAN, ConversationState.ASK_BLOOD_REPORT))
                .thenReturn(true);
        doThrow(new RuntimeException("S3 down")).when(patientIntakeService)
                .storeReport(any(), any(), any(), anyString(), anyString(), any());

        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});

        assertThrows(PortalException.class, () -> service.handleUpload(p.getId(), file));
        verify(patientIntakeService).updateConversationState(p.getId(), ConversationState.ASK_PET_SCAN);
    }
}
