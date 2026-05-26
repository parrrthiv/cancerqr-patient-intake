package com.oncology.intake.service;

import com.oncology.intake.config.CancerQRProtocolConfig;
import com.oncology.intake.dto.WhatsAppWebhookDto.MediaContent;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.repository.DoctorRepository;
import com.oncology.intake.repository.ReportRepository;
import com.oncology.intake.service.WhatsAppClientService.MediaDownloadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the media-upload concurrency + idempotency guard added in the PHI /
 * race-fix work: {@code ConversationService.processMediaMessage} must
 *  - ignore a re-delivered media (same WhatsApp media id), and
 *  - ignore an upload whose step was already claimed by a concurrent event,
 * so a single intake step can never ingest two files (e.g. a blood report
 * mis-stored as a second PET scan).
 *
 * <p>Kept separate from {@link ConversationServiceTest} because the no-reply
 * paths here intentionally send no WhatsApp message, which would clash with
 * that class's strict shared stub. Strictness is LENIENT for the same reason.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversationService — media upload (concurrency + dedup)")
class ConversationServiceMediaTest {

    @Mock private PatientIntakeService patientIntakeService;
    @Mock private WhatsAppClientService whatsAppClient;
    @Mock private AnalysisService analysisService;
    @Mock private AuditService auditService;
    @Mock private TumorBoardService tumorBoardService;
    @Mock private CancerQRProtocolConfig protocolConfig;
    @Mock private DoctorRepository doctorRepository;
    @Mock private ReportRepository reportRepository;

    private ConversationService service;

    private static final String PHONE = "1234567890";
    private static final String MEDIA_ID = "media-123";

    @BeforeEach
    void setUp() {
        service = new ConversationService(
                patientIntakeService, whatsAppClient, analysisService, auditService,
                tumorBoardService, protocolConfig, doctorRepository, reportRepository);
        when(whatsAppClient.sendTextMessage(anyString(), anyString())).thenReturn(Mono.empty());
    }

    private Patient patientInState(ConversationState state) {
        return Patient.builder()
                .id(UUID.randomUUID())
                .whatsappNumber(PHONE)
                .conversationState(state)
                .build();
    }

    private MediaContent media() {
        MediaContent m = new MediaContent();
        m.setId(MEDIA_ID);
        m.setMimeType("image/jpeg");
        m.setFilename("scan.jpg");
        return m;
    }

    @Test
    @DisplayName("re-delivered media (same id) is ignored — not stored twice")
    void duplicateMediaIgnored() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        when(patientIntakeService.findOrCreatePatient(PHONE)).thenReturn(p);
        when(reportRepository.findByWhatsappMediaId(MEDIA_ID)).thenReturn(Optional.of(new Report()));

        service.processMediaMessage(PHONE, media(), "image");

        verify(patientIntakeService, never()).advanceStateIfCurrent(any(), any(), any());
        verify(patientIntakeService, never())
                .storeReport(any(), any(), any(), anyString(), anyString(), anyString());
        verify(whatsAppClient, never()).downloadMediaById(anyString());
    }

    @Test
    @DisplayName("losing the step-claim race is ignored — second upload not stored")
    void raceLostIgnored() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        when(patientIntakeService.findOrCreatePatient(PHONE)).thenReturn(p);
        when(reportRepository.findByWhatsappMediaId(MEDIA_ID)).thenReturn(Optional.empty());
        when(patientIntakeService.advanceStateIfCurrent(
                p.getId(), ConversationState.ASK_PET_SCAN, ConversationState.ASK_BLOOD_REPORT))
                .thenReturn(false);

        service.processMediaMessage(PHONE, media(), "image");

        verify(patientIntakeService, never())
                .storeReport(any(), any(), any(), anyString(), anyString(), anyString());
        verify(whatsAppClient, never()).downloadMediaById(anyString());
    }

    @Test
    @DisplayName("winning the claim stores the PET scan")
    void petScanStoredOnClaim() {
        Patient p = patientInState(ConversationState.ASK_PET_SCAN);
        when(patientIntakeService.findOrCreatePatient(PHONE)).thenReturn(p);
        when(reportRepository.findByWhatsappMediaId(MEDIA_ID)).thenReturn(Optional.empty());
        when(patientIntakeService.advanceStateIfCurrent(
                p.getId(), ConversationState.ASK_PET_SCAN, ConversationState.ASK_BLOOD_REPORT))
                .thenReturn(true);
        when(whatsAppClient.downloadMediaById(MEDIA_ID))
                .thenReturn(Mono.just(new MediaDownloadResult(new byte[]{1, 2, 3}, "image/jpeg", 3L)));

        service.processMediaMessage(PHONE, media(), "image");

        verify(patientIntakeService).storeReport(
                eq(p.getId()), eq(ReportType.PET_SCAN), any(byte[].class),
                anyString(), eq("image/jpeg"), eq(MEDIA_ID));
    }
}
