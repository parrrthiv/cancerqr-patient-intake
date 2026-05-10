package com.oncology.intake.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for WhatsApp Business Cloud API.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "whatsapp.api")
@Validated
public class WhatsAppConfig {

    @NotBlank(message = "WhatsApp API base URL is required")
    private String baseUrl = "https://graph.facebook.com/v18.0";

    @NotBlank(message = "WhatsApp phone number ID is required")
    private String phoneNumberId;

    @NotBlank(message = "WhatsApp access token is required")
    private String accessToken;

    @NotBlank(message = "WhatsApp verify token is required")
    private String verifyToken;

    /**
     * App Secret used to verify {@code X-Hub-Signature-256} on inbound webhooks.
     * If blank, signature verification is skipped — only sensible for local dev.
     * In production, set {@code WHATSAPP_WEBHOOK_SECRET} to your Meta App Secret.
     */
    private String webhookSecret;

    /**
     * Get the messages endpoint URL
     */
    public String getMessagesUrl() {
        return String.format("%s/%s/messages", baseUrl, phoneNumberId);
    }

    /**
     * Get the media URL endpoint
     */
    public String getMediaUrl(String mediaId) {
        return String.format("%s/%s", baseUrl, mediaId);
    }
}
