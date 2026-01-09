package com.oncology.intake.controller;

import com.oncology.intake.config.WhatsAppConfig;
import com.oncology.intake.dto.WhatsAppWebhookDto.*;
import com.oncology.intake.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * WhatsApp Webhook Controller
 * 
 * Handles incoming webhook events from WhatsApp Business Cloud API:
 * - Webhook verification (GET request)
 * - Message events (POST request)
 * - Status updates
 */
@RestController
@RequestMapping("/webhook/whatsapp")
@RequiredArgsConstructor
@Slf4j
public class WhatsAppWebhookController {

    private final WhatsAppConfig whatsAppConfig;
    private final ConversationService conversationService;

    /**
     * Webhook verification endpoint (GET)
     * Called by WhatsApp to verify the webhook URL during setup
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String verifyToken,
            @RequestParam("hub.challenge") String challenge) {
        
        log.info("Webhook verification request received");
        
        if ("subscribe".equals(mode) && whatsAppConfig.getVerifyToken().equals(verifyToken)) {
            log.info("Webhook verification successful");
            return ResponseEntity.ok(challenge);
        } else {
            log.warn("Webhook verification failed - invalid token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
        }
    }

    /**
     * Webhook event receiver (POST)
     * Receives all incoming messages and status updates from WhatsApp
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhook(
            @RequestBody WebhookPayload payload,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) String rawBody) {
        
        log.debug("Webhook event received");
        
        // Verify signature if webhook secret is configured
        if (whatsAppConfig.getWebhookSecret() != null && 
            !whatsAppConfig.getWebhookSecret().isEmpty() &&
            !verifySignature(rawBody, signature)) {
            log.warn("Invalid webhook signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }
        
        // Process the webhook payload
        try {
            processWebhookPayload(payload);
            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            // Return 200 to prevent WhatsApp from retrying
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
    }

    /**
     * Process the webhook payload
     */
    private void processWebhookPayload(WebhookPayload payload) {
        if (payload == null || payload.getEntry() == null) {
            log.debug("Empty webhook payload");
            return;
        }
        
        for (Entry entry : payload.getEntry()) {
            if (entry.getChanges() == null) continue;
            
            for (Change change : entry.getChanges()) {
                if (change.getValue() == null) continue;
                
                Value value = change.getValue();
                
                // Process messages
                if (value.getMessages() != null) {
                    processMessages(value.getMessages(), value.getContacts());
                }
                
                // Process status updates
                if (value.getStatuses() != null) {
                    processStatuses(value.getStatuses());
                }
            }
        }
    }

    /**
     * Process incoming messages
     */
    private void processMessages(List<Message> messages, List<Contact> contacts) {
        for (Message message : messages) {
            String from = message.getFrom();
            String messageId = message.getId();
            String messageType = message.getType();
            
            log.info("Processing {} message from: {}", messageType, maskNumber(from));
            
            switch (messageType) {
                case "text" -> {
                    if (message.getText() != null) {
                        conversationService.processTextMessage(
                                from, 
                                message.getText().getBody(), 
                                messageId
                        );
                    }
                }
                
                case "interactive" -> {
                    if (message.getInteractive() != null) {
                        processInteractiveMessage(from, message.getInteractive());
                    }
                }
                
                case "button" -> {
                    if (message.getButton() != null) {
                        conversationService.processInteractiveResponse(
                                from,
                                message.getButton().getPayload(),
                                message.getButton().getText()
                        );
                    }
                }
                
                case "image" -> {
                    if (message.getImage() != null) {
                        conversationService.processMediaMessage(
                                from, 
                                message.getImage(), 
                                "image"
                        );
                    }
                }
                
                case "document" -> {
                    if (message.getDocument() != null) {
                        conversationService.processMediaMessage(
                                from, 
                                message.getDocument(), 
                                "document"
                        );
                    }
                }
                
                default -> log.debug("Unsupported message type: {}", messageType);
            }
        }
    }

    /**
     * Process interactive message responses
     */
    private void processInteractiveMessage(String from, Interactive interactive) {
        String responseId = null;
        String title = null;
        
        if ("button_reply".equals(interactive.getType()) && interactive.getButtonReply() != null) {
            responseId = interactive.getButtonReply().getId();
            title = interactive.getButtonReply().getTitle();
        } else if ("list_reply".equals(interactive.getType()) && interactive.getListReply() != null) {
            responseId = interactive.getListReply().getId();
            title = interactive.getListReply().getTitle();
        }
        
        if (responseId != null) {
            conversationService.processInteractiveResponse(from, responseId, title);
        }
    }

    /**
     * Process status updates
     */
    private void processStatuses(List<Status> statuses) {
        for (Status status : statuses) {
            log.debug("Message {} status: {} for recipient: {}", 
                    status.getId(), 
                    status.getStatus(), 
                    maskNumber(status.getRecipientId()));
            
            // Could track delivery/read status here if needed
            switch (status.getStatus()) {
                case "sent" -> log.debug("Message sent");
                case "delivered" -> log.debug("Message delivered");
                case "read" -> log.debug("Message read");
                case "failed" -> {
                    log.warn("Message failed to deliver");
                    if (status.getErrors() != null) {
                        for (var error : status.getErrors()) {
                            log.warn("Error: {} - {}", error.getCode(), error.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * Verify the webhook signature
     */
    private boolean verifySignature(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        
        try {
            String secret = whatsAppConfig.getWebhookSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + HexFormat.of().formatHex(hash);
            
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }

    /**
     * Mask phone number for logging (privacy)
     */
    private String maskNumber(String number) {
        if (number == null || number.length() < 8) return "***";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }
}
