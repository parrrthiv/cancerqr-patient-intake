package com.oncology.intake.exception;

import com.oncology.intake.exception.IntakeExceptions.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST endpoints.
 * Ensures consistent error response format and proper logging.
 * 
 * PRIVACY NOTE: Error messages should not contain PHI.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePatientNotFound(PatientNotFoundException ex) {
        log.warn("Patient not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReportNotFound(ReportNotFoundException ex) {
        log.warn("Report not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInput(InvalidInputException ex) {
        log.warn("Invalid input for field {}: {}", ex.getField(), ex.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "INVALID_INPUT");
        response.put("message", ex.getMessage());
        response.put("field", ex.getField());
        response.put("expectedFormat", ex.getExpectedFormat());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(WhatsAppApiException.class)
    public ResponseEntity<Map<String, Object>> handleWhatsAppApiError(WhatsAppApiException ex) {
        log.error("WhatsApp API error: {} (status: {}, code: {})", 
                ex.getMessage(), ex.getStatusCode(), ex.getErrorCode());
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_GATEWAY.value());
        response.put("error", "WHATSAPP_API_ERROR");
        response.put("message", "Failed to communicate with WhatsApp");
        response.put("errorCode", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    @ExceptionHandler(MediaDownloadException.class)
    public ResponseEntity<Map<String, Object>> handleMediaDownloadError(MediaDownloadException ex) {
        log.error("Media download error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "MEDIA_DOWNLOAD_ERROR", 
                "Failed to download media file");
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageError(StorageException ex) {
        log.error("Storage error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", 
                "Failed to store or retrieve file");
    }

    @ExceptionHandler(FormulaEngineException.class)
    public ResponseEntity<Map<String, Object>> handleFormulaEngineError(FormulaEngineException ex) {
        log.error("Formula engine error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "FORMULA_ERROR", 
                "Failed to generate analysis");
    }

    @ExceptionHandler(ConversationStateException.class)
    public ResponseEntity<Map<String, Object>> handleConversationStateError(ConversationStateException ex) {
        log.warn("Conversation state error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "CONVERSATION_STATE_ERROR", ex.getMessage());
    }

    @ExceptionHandler(WebhookVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleWebhookVerificationError(WebhookVerificationException ex) {
        log.warn("Webhook verification failed: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "WEBHOOK_VERIFICATION_FAILED", 
                "Webhook verification failed");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", 
                "Too many requests. Please try again later.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {
        // Log full stack trace for unexpected errors
        log.error("Unexpected error occurred", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", 
                "An unexpected error occurred. Please try again later.");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String errorCode, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", status.value());
        response.put("error", errorCode);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
