package com.oncology.intake.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncology.intake.config.WhatsAppConfig;
import com.oncology.intake.dto.WhatsAppWebhookDto.*;
import com.oncology.intake.service.ConversationService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * WhatsApp Cloud API webhook receiver.
 *
 * Inbound flow:
 *  1. Increment {@code webhook.received}.
 *  2. If {@code whatsapp.api.webhook-secret} is configured, verify the
 *     {@code X-Hub-Signature-256} header against {@code HMAC-SHA256(secret, raw body)}
 *     using a constant-time comparison. Reject with 401 on mismatch.
 *  3. Parse the raw body to {@link WebhookPayload} via the shared {@link ObjectMapper}.
 *  4. Dispatch each contained message/status to {@link ConversationService} —
 *     this happens synchronously here, but ConversationService methods are
 *     {@code @Async} so they return immediately.
 *  5. Always return 200 to Meta on processing errors (otherwise Meta retries
 *     and we double-process).
 *
 * The single {@code @RequestBody String rawBody} is critical: the request body is
 * a non-rewindable stream, so we cannot bind both a parsed POJO *and* the raw bytes.
 * If you ever change this, also revisit the signature check — HMAC must be computed
 * over the *exact* bytes Meta signed, not over a re-serialized JSON string.
 */
@RestController
@RequestMapping("/webhook/whatsapp")
@RequiredArgsConstructor
@Slf4j
public class WhatsAppWebhookController {

    private final WhatsAppConfig whatsAppConfig;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Webhook verification handshake (GET) — Meta calls this once during setup.
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
        }
        log.warn("Webhook verification failed - invalid token");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    /**
     * Webhook event receiver (POST). See class javadoc for flow.
     */
    @PostMapping
    public ResponseEntity<String> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        meterRegistry.counter("webhook.received").increment();

        // 1. Signature check (skipped if no secret configured — dev/local only).
        String secret = whatsAppConfig.getWebhookSecret();
        if (secret != null && !secret.isBlank()) {
            if (signature == null || !verifySignature(rawBody, signature, secret)) {
                meterRegistry.counter("webhook.signature_failed").increment();
                log.warn("Rejected webhook: signature missing or invalid");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            }
        } else {
            log.debug("Webhook signature verification skipped (no secret configured)");
        }

        // 2. Parse.
        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            meterRegistry.counter("webhook.parse_failed").increment();
            log.error("Failed to parse webhook payload", e);
            // Return 200 — Meta retrying garbage doesn't help anyone.
            return ResponseEntity.ok("EVENT_RECEIVED");
        }

        // 3. Dispatch.
        try {
            processWebhookPayload(payload);
        } catch (Exception e) {
            meterRegistry.counter("webhook.processing_failed").increment();
            log.error("Error processing webhook payload", e);
            // Always 200 — see class javadoc.
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    /**
     * HMAC-SHA256 the raw body with the App Secret, compare in constant time
     * against the {@code sha256=...} value Meta sent in {@code X-Hub-Signature-256}.
     */
    private boolean verifySignature(String rawBody, String headerSignature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(computed);

            // MessageDigest.isEqual is constant-time on equal-length inputs.
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    headerSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error computing webhook signature", e);
            return false;
        }
    }

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

                if (value.getMessages() != null) {
                    processMessages(value.getMessages(), value.getContacts());
                }
                if (value.getStatuses() != null) {
                    processStatuses(value.getStatuses());
                }
            }
        }
    }

    private void processMessages(List<Message> messages, List<Contact> contacts) {
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
            meterRegistry.counter("webhook.message_received", "type", messageType).increment();

            switch (messageType) {
                case "text" -> {
                    if (message.getText() != null) {
                        conversationService.processTextMessage(
                                from, message.getText().getBody(), messageId, contactName);
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
                                from, message.getButton().getPayload(), message.getButton().getText());
                    }
                }
                case "image" -> {
                    if (message.getImage() != null) {
                        conversationService.processMediaMessage(from, message.getImage(), "image");
                    }
                }
                case "document" -> {
                    if (message.getDocument() != null) {
                        conversationService.processMediaMessage(from, message.getDocument(), "document");
                    }
                }
                default -> log.debug("Unsupported message type: {}", messageType);
            }
        }
    }

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

    private void processStatuses(List<Status> statuses) {
        for (Status status : statuses) {
            log.debug("Message {} status: {} for recipient: {}",
                    status.getId(), status.getStatus(), maskNumber(status.getRecipientId()));

            switch (status.getStatus()) {
                case "sent"      -> log.debug("Message sent");
                case "delivered" -> log.debug("Message delivered");
                case "read"      -> log.debug("Message read");
                case "failed"    -> {
                    log.warn("Message failed to deliver");
                    meterRegistry.counter("webhook.message_delivery_failed").increment();
                    if (status.getErrors() != null) {
                        for (var error : status.getErrors()) {
                            log.warn("Error: {} - {}", error.getCode(), error.getMessage());
                        }
                    }
                }
            }
        }
    }

    /** Mask phone number for logging (privacy). */
    private String maskNumber(String number) {
        if (number == null || number.length() < 8) return "***";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }
}
