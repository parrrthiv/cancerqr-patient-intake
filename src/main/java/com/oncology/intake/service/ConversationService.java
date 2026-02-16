package com.oncology.intake.service;

import com.oncology.intake.dto.AnalysisDto.AnalysisResult;
import com.oncology.intake.dto.AnalysisDto.FormattedAnalysisMessage;
import com.oncology.intake.dto.WhatsAppWebhookDto.*;
import com.oncology.intake.entity.AuditLog.AuditAction;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.entity.Report.ReportType;
import com.oncology.intake.exception.IntakeExceptions.InvalidInputException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation service handling the WhatsApp chat flow state machine.
 * Manages state transitions and input validation for patient intake.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final PatientIntakeService patientIntakeService;
    private final WhatsAppClientService whatsAppClient;
    private final AnalysisService analysisService;
    private final AuditService auditService;

    // Validation patterns
    private static final Pattern AGE_PATTERN = Pattern.compile("^\\d{1,3}$");
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,2})?$");
    private static final Pattern PAIN_SCALE_PATTERN = Pattern.compile("^(10|[0-9])$");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern PAIN_BUTTON_PATTERN = Pattern.compile("^pain_(\\d+)$");

    // Messages
    private static final String WELCOME_MESSAGE = """
            👋 Welcome to the Cancer Care Initial Assessment System.
            
            I'm here to help collect some basic information to assist your healthcare team.
            
            ⚠️ *Important:* This system provides initial suggestions only and does NOT replace professional medical advice.
            
            By continuing, you consent to:
            • Collection of your health information
            • Storage of your medical reports
            • Generation of initial assessment suggestions
            
            Your data is encrypted and handled confidentially.
            
            Reply *YES* to continue or *NO* to stop.""";

    private static final String ASK_AGE_MESSAGE = """
            Let's start with some basic information.
            
            📅 *What is your age?*
            
            Please enter a number between 0 and 120.""";

    private static final String ASK_WEIGHT_MESSAGE = """
            ⚖️ *What is your body weight in kilograms (kg)?*
            
            Please enter a number (e.g., 65 or 72.5).""";

    private static final String ASK_PAIN_SCALE_MESSAGE = """
            📊 *On a scale of 0-10, how would you rate your current pain level?*
            
            0 = No pain at all
            5 = Moderate pain
            10 = Worst pain imaginable
            
            Please enter a number from 0 to 10.""";

    private static final String ASK_DIAGNOSIS_DATE_MESSAGE = """
            📆 *What was your date of diagnosis?*
            
            Please enter the date in this format: YYYY-MM-DD
            
            Example: 2024-03-15""";

    private static final String ASK_PET_SCAN_MESSAGE = """
            📷 *Please upload your PET Scan report.*
            
            You can send:
            • A photo/image of the report
            • A PDF document
            
            Simply attach the file in this chat.""";

    private static final String ASK_BLOOD_REPORT_MESSAGE = """
            🩸 *Please upload your Blood Report.*
            
            You can send:
            • A photo/image of the report
            • A PDF document
            
            Simply attach the file in this chat.""";

    private static final String PROCESSING_MESSAGE = """
            ✅ Thank you for providing all the information!
            
            🔄 *Generating your initial assessment...*
            
            Please wait a moment.""";

    private static final String COMPLETION_MESSAGE = """
            ✅ *Assessment Complete!*
            
            Thank you for using our system.
            
            *Next Steps:*
            1. Review the assessment above
            2. Share it with your oncologist
            3. Schedule a consultation to discuss treatment
            
            If you have questions, please contact your healthcare provider.
            
            Take care! 💙""";

    /**
     * Process an incoming text message
     */
    @Async
    public void processTextMessage(String whatsappNumber, String messageText, String messageId, String contactName) {
        log.info("Processing text message from: {}", maskNumber(whatsappNumber));

        Patient patient = patientIntakeService.findOrCreatePatient(whatsappNumber, contactName);
        ConversationState currentState = patient.getConversationState();
        
        auditService.logWhatsAppEvent(patient.getId(), AuditAction.WHATSAPP_MESSAGE_RECEIVED,
                "State: " + currentState, null);
        
        try {
            switch (currentState) {
                case INITIAL -> handleInitialState(patient);
                case AWAITING_CONSENT -> handleConsentResponse(patient, messageText);
                case ASK_AGE -> handleAgeInput(patient, messageText);
                case ASK_WEIGHT -> handleWeightInput(patient, messageText);
                case ASK_PAIN_SCALE -> handlePainScaleInput(patient, messageText);
                case ASK_DIAGNOSIS_DATE -> handleDiagnosisDateInput(patient, messageText);
                case ASK_PET_SCAN, ASK_BLOOD_REPORT -> 
                        sendMessage(patient.getWhatsappNumber(), 
                                "Please upload the requested document as an image or PDF.");
                case PROCESSING -> sendMessage(patient.getWhatsappNumber(), 
                        "⏳ Your assessment is being processed. Please wait...");
                case RESULT_SENT, COMPLETED -> handleCompletedState(patient, messageText);
                case EXPIRED -> handleExpiredState(patient);
            }
        } catch (Exception e) {
            log.error("Error processing message for patient: {}", patient.getId(), e);
            sendMessage(whatsappNumber, 
                    "Sorry, there was an error processing your message. Please try again.");
        }
    }

    /**
     * Process an incoming interactive response (button/list selection)
     */
    @Async
    public void processInteractiveResponse(String whatsappNumber, String responseId, String title) {
        log.info("Processing interactive response from: {}", maskNumber(whatsappNumber));
        
        Patient patient = patientIntakeService.findOrCreatePatient(whatsappNumber);
        ConversationState currentState = patient.getConversationState();
        
        // Check if this is a pain scale selection
        Matcher painMatcher = PAIN_BUTTON_PATTERN.matcher(responseId);
        if (painMatcher.matches() && currentState == ConversationState.ASK_PAIN_SCALE) {
            String painValue = painMatcher.group(1);
            handlePainScaleInput(patient, painValue);
            return;
        }
        
        // Handle other interactive responses
        log.debug("Interactive response: {} - {}", responseId, title);
    }

    /**
     * Process an incoming media message (image/document)
     */
    @Async
    public void processMediaMessage(String whatsappNumber, MediaContent media, String mediaType) {
        log.info("Processing {} from: {}", mediaType, maskNumber(whatsappNumber));
        
        Patient patient = patientIntakeService.findOrCreatePatient(whatsappNumber);
        ConversationState currentState = patient.getConversationState();
        
        auditService.logWhatsAppEvent(patient.getId(), AuditAction.WHATSAPP_MEDIA_RECEIVED,
                "Type: " + mediaType + ", State: " + currentState, null);
        
        // Determine report type based on current state
        ReportType reportType = switch (currentState) {
            case ASK_PET_SCAN -> ReportType.PET_SCAN;
            case ASK_BLOOD_REPORT -> ReportType.BLOOD_REPORT;
            default -> null;
        };
        
        if (reportType == null) {
            sendMessage(whatsappNumber, 
                    "Thank you, but we're not expecting a document upload at this stage. " +
                    "Please follow the current prompt.");
            return;
        }
        
        // Download and store the media
        processAndStoreMedia(patient, media, reportType, mediaType);
    }

    // =============== State Handlers ===============

    private void handleInitialState(Patient patient) {
        sendMessage(patient.getWhatsappNumber(), WELCOME_MESSAGE);
        patientIntakeService.updateConversationState(patient.getId(), ConversationState.AWAITING_CONSENT);
    }

    private void handleConsentResponse(Patient patient, String messageText) {
        String response = messageText.trim().toUpperCase();
        
        if (response.equals("YES") || response.equals("Y")) {
            patientIntakeService.recordConsent(patient.getId());
            sendMessage(patient.getWhatsappNumber(), ASK_AGE_MESSAGE);
            patientIntakeService.updateConversationState(patient.getId(), ConversationState.ASK_AGE);
        } else if (response.equals("NO") || response.equals("N")) {
            sendMessage(patient.getWhatsappNumber(), 
                    "Thank you. Your data will not be collected. " +
                    "If you change your mind, you can start a new conversation anytime.");
            patientIntakeService.updateConversationState(patient.getId(), ConversationState.COMPLETED);
        } else {
            sendMessage(patient.getWhatsappNumber(), 
                    "Please reply *YES* to continue or *NO* to stop.");
        }
    }

    private void handleAgeInput(Patient patient, String messageText) {
        String input = messageText.trim();
        
        if (!AGE_PATTERN.matcher(input).matches()) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter a valid age as a number (e.g., 45).");
            return;
        }
        
        int age = Integer.parseInt(input);
        if (age < 0 || age > 120) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter an age between 0 and 120.");
            return;
        }
        
        patientIntakeService.updateAge(patient.getId(), age);
        sendMessage(patient.getWhatsappNumber(), 
                String.format("✅ Age recorded: %d years\n\n", age) + ASK_WEIGHT_MESSAGE);
        patientIntakeService.updateConversationState(patient.getId(), ConversationState.ASK_WEIGHT);
    }

    private void handleWeightInput(Patient patient, String messageText) {
        String input = messageText.trim().replace(",", ".");
        
        if (!WEIGHT_PATTERN.matcher(input).matches()) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter a valid weight in kg (e.g., 65 or 72.5).");
            return;
        }
        
        BigDecimal weight = new BigDecimal(input);
        if (weight.compareTo(BigDecimal.ONE) < 0 || weight.compareTo(BigDecimal.valueOf(300)) > 0) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter a weight between 1 and 300 kg.");
            return;
        }
        
        patientIntakeService.updateWeight(patient.getId(), weight);
        
        // Use interactive pain scale selector if supported, otherwise text
        try {
            whatsAppClient.sendPainScaleSelector(patient.getWhatsappNumber()).block();
        } catch (Exception e) {
            // Fallback to text if interactive fails
            sendMessage(patient.getWhatsappNumber(), 
                    String.format("✅ Weight recorded: %.1f kg\n\n", weight) + ASK_PAIN_SCALE_MESSAGE);
        }
        
        patientIntakeService.updateConversationState(patient.getId(), ConversationState.ASK_PAIN_SCALE);
    }

    private void handlePainScaleInput(Patient patient, String messageText) {
        String input = messageText.trim();
        
        // Check for interactive response format
        Matcher painMatcher = PAIN_BUTTON_PATTERN.matcher(input);
        if (painMatcher.matches()) {
            input = painMatcher.group(1);
        }
        
        if (!PAIN_SCALE_PATTERN.matcher(input).matches()) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter a number from 0 to 10.");
            return;
        }
        
        int painScale = Integer.parseInt(input);
        patientIntakeService.updatePainScale(patient.getId(), painScale);
        
        String painDescription = getPainDescription(painScale);
        sendMessage(patient.getWhatsappNumber(), 
                String.format("✅ Pain level recorded: %d/10 (%s)\n\n", painScale, painDescription) + 
                ASK_DIAGNOSIS_DATE_MESSAGE);
        patientIntakeService.updateConversationState(patient.getId(), ConversationState.ASK_DIAGNOSIS_DATE);
    }

    private void handleDiagnosisDateInput(Patient patient, String messageText) {
        String input = messageText.trim();
        
        if (!DATE_PATTERN.matcher(input).matches()) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Please enter the date in YYYY-MM-DD format (e.g., 2024-03-15).");
            return;
        }
        
        LocalDate diagnosisDate;
        try {
            diagnosisDate = LocalDate.parse(input, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Invalid date. Please use YYYY-MM-DD format (e.g., 2024-03-15).");
            return;
        }
        
        if (diagnosisDate.isAfter(LocalDate.now())) {
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Diagnosis date cannot be in the future. Please enter a valid past date.");
            return;
        }
        
        patientIntakeService.updateDiagnosisDate(patient.getId(), diagnosisDate);
        sendMessage(patient.getWhatsappNumber(), 
                String.format("✅ Diagnosis date recorded: %s\n\n", diagnosisDate) + ASK_PET_SCAN_MESSAGE);
        patientIntakeService.updateConversationState(patient.getId(), ConversationState.ASK_PET_SCAN);
    }

    private void handleCompletedState(Patient patient, String messageText) {
        String input = messageText.trim().toUpperCase();
        
        if (input.equals("START") || input.equals("RESTART") || input.equals("NEW")) {
            patientIntakeService.resetPatientIntake(patient.getId());
            handleInitialState(patient);
        } else {
            sendMessage(patient.getWhatsappNumber(), 
                    "Your assessment has been completed. " +
                    "If you need a new assessment, reply *START* to begin again.");
        }
    }

    private void handleExpiredState(Patient patient) {
        sendMessage(patient.getWhatsappNumber(), 
                "Your previous session has expired. Let's start fresh!\n\n" +
                "Reply *START* to begin a new assessment.");
        patientIntakeService.resetPatientIntake(patient.getId());
    }

    // =============== Media Processing ===============

    private void processAndStoreMedia(Patient patient, MediaContent media, 
                                       ReportType reportType, String mediaType) {
        try {
            sendMessage(patient.getWhatsappNumber(), "📥 Receiving your document...");
            
            // Download media from WhatsApp
            var downloadResult = whatsAppClient.downloadMediaById(media.getId()).block();
            
            if (downloadResult == null || downloadResult.content() == null) {
                sendMessage(patient.getWhatsappNumber(), 
                        "❌ Failed to download the document. Please try uploading again.");
                return;
            }
            
            // Determine filename
            String fileName = media.getFilename();
            if (fileName == null || fileName.isEmpty()) {
                fileName = reportType.name().toLowerCase() + "_" + 
                          System.currentTimeMillis() + getExtension(downloadResult.mimeType());
            }
            
            // Store the report
            patientIntakeService.storeReport(
                    patient.getId(),
                    reportType,
                    downloadResult.content(),
                    fileName,
                    downloadResult.mimeType(),
                    media.getId()
            );
            
            // Move to next state
            if (reportType == ReportType.PET_SCAN) {
                sendMessage(patient.getWhatsappNumber(), 
                        "✅ PET Scan report received!\n\n" + ASK_BLOOD_REPORT_MESSAGE);
                patientIntakeService.updateConversationState(
                        patient.getId(), ConversationState.ASK_BLOOD_REPORT);
            } else if (reportType == ReportType.BLOOD_REPORT) {
                sendMessage(patient.getWhatsappNumber(), PROCESSING_MESSAGE);
                patientIntakeService.updateConversationState(
                        patient.getId(), ConversationState.PROCESSING);
                
                // Generate and send analysis
                generateAndSendAnalysis(patient);
            }
            
        } catch (Exception e) {
            log.error("Failed to process media for patient: {}", patient.getId(), e);
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Error processing your document. Please try uploading again.");
        }
    }

    private void generateAndSendAnalysis(Patient patient) {
        try {
            // Mark intake as completed
            patientIntakeService.markIntakeCompleted(patient.getId());
            
            // Generate analysis
            AnalysisResult result = analysisService.generateAnalysis(patient.getId());
            
            // Format for WhatsApp
            FormattedAnalysisMessage formatted = analysisService.formatForWhatsApp(result);
            
            // Send the analysis (may need to split if too long)
            String fullMessage = formatted.getFullMessage();
            if (fullMessage.length() > 4000) {
                // Send in parts
                sendMessage(patient.getWhatsappNumber(), formatted.getPatientSummary());
                sendMessage(patient.getWhatsappNumber(), formatted.getMedicineSection());
                if (!formatted.getSupportiveCareSection().isEmpty()) {
                    sendMessage(patient.getWhatsappNumber(), formatted.getSupportiveCareSection());
                }
                sendMessage(patient.getWhatsappNumber(), formatted.getDisclaimer());
            } else {
                sendMessage(patient.getWhatsappNumber(), fullMessage);
            }
            
            // Mark as sent
            var analysis = analysisService.getLatestAnalysis(patient.getId());
            analysis.ifPresent(a -> analysisService.markAsSent(a.getId()));
            
            // Send completion message
            sendMessage(patient.getWhatsappNumber(), COMPLETION_MESSAGE);
            
            // Update state
            patientIntakeService.updateConversationState(
                    patient.getId(), ConversationState.RESULT_SENT);
            
        } catch (Exception e) {
            log.error("Failed to generate analysis for patient: {}", patient.getId(), e);
            sendMessage(patient.getWhatsappNumber(), 
                    "❌ Sorry, there was an error generating your assessment. " +
                    "Our team has been notified and will contact you shortly.");
            
            auditService.logFailedAction(patient.getId(), AuditAction.ANALYSIS_GENERATED,
                    "Failed to generate analysis", e.getMessage());
        }
    }

    // =============== Helper Methods ===============

    private void sendMessage(String to, String message) {
        try {
            whatsAppClient.sendTextMessage(to, message).block();
        } catch (Exception e) {
            log.error("Failed to send message to: {}", maskNumber(to), e);
        }
    }

    private String getPainDescription(int painScale) {
        return switch (painScale) {
            case 0 -> "No pain";
            case 1, 2, 3 -> "Mild pain";
            case 4, 5, 6 -> "Moderate pain";
            case 7, 8 -> "Severe pain";
            case 9, 10 -> "Extreme pain";
            default -> "Unknown";
        };
    }

    private String getExtension(String mimeType) {
        if (mimeType == null) return "";
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private String maskNumber(String number) {
        if (number == null || number.length() < 8) return "***";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }
}
