package com.oncology.intake.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * DTOs for WhatsApp Business Cloud API webhook events.
 * Based on WhatsApp Cloud API v18.0 documentation.
 */
public class WhatsAppWebhookDto {

    /**
     * Root webhook payload from WhatsApp
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebhookPayload {
        private String object;
        private List<Entry> entry;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private String id;
        private List<Change> changes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Change {
        private String field;
        private Value value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        @JsonProperty("messaging_product")
        private String messagingProduct;
        
        private Metadata metadata;
        private List<Contact> contacts;
        private List<Message> messages;
        private List<Status> statuses;
        private List<Error> errors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        @JsonProperty("display_phone_number")
        private String displayPhoneNumber;
        
        @JsonProperty("phone_number_id")
        private String phoneNumberId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        private Profile profile;
        
        @JsonProperty("wa_id")
        private String waId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String id;
        private String from;
        private String timestamp;
        private String type;
        
        // For text messages
        private TextContent text;
        
        // For media messages (image, document)
        private MediaContent image;
        private MediaContent document;
        private MediaContent audio;
        private MediaContent video;
        
        // For interactive responses
        private Interactive interactive;
        
        // For button responses
        private Button button;
        
        // Context for replies
        private Context context;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TextContent {
        private String body;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaContent {
        private String id;
        
        @JsonProperty("mime_type")
        private String mimeType;
        
        private String sha256;
        private String caption;
        private String filename;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Interactive {
        private String type;
        
        @JsonProperty("button_reply")
        private ButtonReply buttonReply;
        
        @JsonProperty("list_reply")
        private ListReply listReply;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ButtonReply {
        private String id;
        private String title;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListReply {
        private String id;
        private String title;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Button {
        private String payload;
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Context {
        private String from;
        private String id;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        private String id;
        
        @JsonProperty("recipient_id")
        private String recipientId;
        
        private String status;
        private String timestamp;
        private Conversation conversation;
        private Pricing pricing;
        private List<Error> errors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Conversation {
        private String id;
        
        @JsonProperty("expiration_timestamp")
        private String expirationTimestamp;
        
        private Origin origin;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Origin {
        private String type;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pricing {
        private String category;
        
        @JsonProperty("pricing_model")
        private String pricingModel;
        
        private boolean billable;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        private int code;
        private String title;
        private String message;
        
        @JsonProperty("error_data")
        private ErrorData errorData;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorData {
        private String details;
    }
}
