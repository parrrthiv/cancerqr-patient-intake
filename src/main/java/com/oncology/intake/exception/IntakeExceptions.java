package com.oncology.intake.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Custom exceptions for the cancer intake system.
 */
public class IntakeExceptions {

    /**
     * Patient not found exception
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class PatientNotFoundException extends RuntimeException {
        public PatientNotFoundException(String identifier) {
            super("Patient not found: " + identifier);
        }
    }

    /**
     * Report not found exception
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(String identifier) {
            super("Report not found: " + identifier);
        }
    }

    /**
     * Analysis not found exception
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class AnalysisNotFoundException extends RuntimeException {
        public AnalysisNotFoundException(String identifier) {
            super("Analysis not found: " + identifier);
        }
    }

    /**
     * Invalid input exception
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidInputException extends RuntimeException {
        private final String field;
        private final String expectedFormat;

        public InvalidInputException(String field, String message, String expectedFormat) {
            super(String.format("Invalid %s: %s", field, message));
            this.field = field;
            this.expectedFormat = expectedFormat;
        }

        public String getField() {
            return field;
        }

        public String getExpectedFormat() {
            return expectedFormat;
        }
    }

    /**
     * WhatsApp API exception
     */
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public static class WhatsAppApiException extends RuntimeException {
        private final int statusCode;
        private final String errorCode;

        public WhatsAppApiException(String message, int statusCode, String errorCode) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * Media download exception
     */
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public static class MediaDownloadException extends RuntimeException {
        public MediaDownloadException(String message, Throwable cause) {
            super("Failed to download media: " + message, cause);
        }

        public MediaDownloadException(String message) {
            super("Failed to download media: " + message);
        }
    }

    /**
     * Storage exception
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super("Storage error: " + message, cause);
        }

        public StorageException(String message) {
            super("Storage error: " + message);
        }
    }

    /**
     * Formula engine exception
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public static class FormulaEngineException extends RuntimeException {
        public FormulaEngineException(String message, Throwable cause) {
            super("Formula engine error: " + message, cause);
        }

        public FormulaEngineException(String message) {
            super("Formula engine error: " + message);
        }
    }

    /**
     * Conversation state exception
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class ConversationStateException extends RuntimeException {
        public ConversationStateException(String message) {
            super("Conversation state error: " + message);
        }
    }

    /**
     * Webhook verification exception
     */
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public static class WebhookVerificationException extends RuntimeException {
        public WebhookVerificationException(String message) {
            super("Webhook verification failed: " + message);
        }
    }

    /**
     * Rate limit exceeded exception
     */
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super("Rate limit exceeded: " + message);
        }
    }
}
