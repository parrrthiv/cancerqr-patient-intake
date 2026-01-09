package com.oncology.intake.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * DTOs for sending messages via WhatsApp Business Cloud API.
 * Supports text, interactive buttons, lists, and media messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsAppMessageDto {

    /**
     * Base message request
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        @JsonProperty("messaging_product")
        @Builder.Default
        private String messagingProduct = "whatsapp";
        
        @JsonProperty("recipient_type")
        @Builder.Default
        private String recipientType = "individual";
        
        private String to;
        private String type;
        
        // For text messages
        private TextBody text;
        
        // For interactive messages
        private InteractiveBody interactive;
        
        // For media messages
        private MediaBody image;
        private MediaBody document;
        
        // For template messages
        private TemplateBody template;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextBody {
        @JsonProperty("preview_url")
        private Boolean previewUrl;
        
        private String body;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractiveBody {
        private String type;  // "button", "list"
        private Header header;
        private Body body;
        private Footer footer;
        private Action action;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String type;  // "text", "image", "document"
        private String text;
        private MediaRef image;
        private MediaRef document;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Footer {
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        // For button type
        private List<Button> buttons;
        
        // For list type
        private String button;
        private List<Section> sections;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Button {
        private String type;  // "reply"
        private Reply reply;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reply {
        private String id;
        private String title;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        private String title;
        private List<Row> rows;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String id;
        private String title;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaBody {
        private String id;
        private String link;
        private String caption;
        private String filename;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaRef {
        private String id;
        private String link;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateBody {
        private String name;
        private Language language;
        private List<Component> components;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Language {
        private String code;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Component {
        private String type;
        private List<Parameter> parameters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Parameter {
        private String type;
        private String text;
    }

    // =============== Response DTOs ===============

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageResponse {
        @JsonProperty("messaging_product")
        private String messagingProduct;
        
        private List<MessageContact> contacts;
        private List<MessageInfo> messages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageContact {
        private String input;
        
        @JsonProperty("wa_id")
        private String waId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageInfo {
        private String id;
    }

    // =============== Media URL Response ===============

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaUrlResponse {
        private String url;
        
        @JsonProperty("mime_type")
        private String mimeType;
        
        private String sha256;
        
        @JsonProperty("file_size")
        private Long fileSize;
        
        private String id;
        
        @JsonProperty("messaging_product")
        private String messagingProduct;
    }
}
