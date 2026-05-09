package com.oncology.intake.controller;

import com.oncology.intake.config.WhatsAppConfig;
import com.oncology.intake.dto.WhatsAppWebhookDto.*;
import com.oncology.intake.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * Receives all incoming messages and status updates from WhatsApp.
     *
     * NOTE: Webhook signature verification (X-Hub-Signature-256) is intentionally
     * not performed. If you want to enforce that requests truly come from Meta,
     * reintroduce HMAC-SHA256 verification using the App Secret and read the
     * request body once as raw bytes (do not declare two @RequestBody params).
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhook(
            @RequestBody WebhookPayload payload) {

        log.debug("Webhook event received");

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
        // Extract contact name from the contacts list
        String contactName = null;
        if (contacts != null && !contacts.isEmpty()) {
            Contact contact = contacts.get(0);
            if (contact.getProfile() != null) {
                contactName = contact.getProfile().getName();
            }
        }

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
                                messageId,
                                contactName
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
     * Mask phone number for logging (privacy)
     */
    private String maskNumber(String number) {
        if (number == null || number.length() < 8) return "***";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }
}
