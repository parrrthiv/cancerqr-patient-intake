package com.oncology.intake.service;

import com.oncology.intake.config.WhatsAppConfig;
import com.oncology.intake.dto.WhatsAppMessageDto.*;
import com.oncology.intake.exception.IntakeExceptions.MediaDownloadException;
import com.oncology.intake.exception.IntakeExceptions.WhatsAppApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * Client service for WhatsApp Business Cloud API.
 * Handles sending messages and downloading media.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppClientService {

    private final WebClient whatsAppWebClient;
    private final WhatsAppConfig whatsAppConfig;

    /**
     * Send a text message
     */
    public Mono<SendMessageResponse> sendTextMessage(String to, String message) {
        SendMessageRequest request = SendMessageRequest.builder()
                .to(to)
                .type("text")
                .text(TextBody.builder().body(message).build())
                .build();

        return sendMessage(request);
    }

    /**
     * Send an interactive button message
     */
    public Mono<SendMessageResponse> sendButtonMessage(String to, String bodyText, 
                                                         String footerText,
                                                         List<ButtonOption> options) {
        List<Button> buttons = options.stream()
                .map(opt -> Button.builder()
                        .type("reply")
                        .reply(Reply.builder()
                                .id(opt.id())
                                .title(opt.title())
                                .build())
                        .build())
                .toList();

        InteractiveBody interactive = InteractiveBody.builder()
                .type("button")
                .body(Body.builder().text(bodyText).build())
                .footer(footerText != null ? Footer.builder().text(footerText).build() : null)
                .action(Action.builder().buttons(buttons).build())
                .build();

        SendMessageRequest request = SendMessageRequest.builder()
                .to(to)
                .type("interactive")
                .interactive(interactive)
                .build();

        return sendMessage(request);
    }

    /**
     * Send an interactive list message (for pain scale selection)
     */
    public Mono<SendMessageResponse> sendListMessage(String to, String bodyText,
                                                       String buttonText,
                                                       String sectionTitle,
                                                       List<ListOption> options) {
        List<Row> rows = options.stream()
                .map(opt -> Row.builder()
                        .id(opt.id())
                        .title(opt.title())
                        .description(opt.description())
                        .build())
                .toList();

        Section section = Section.builder()
                .title(sectionTitle)
                .rows(rows)
                .build();

        InteractiveBody interactive = InteractiveBody.builder()
                .type("list")
                .body(Body.builder().text(bodyText).build())
                .action(Action.builder()
                        .button(buttonText)
                        .sections(List.of(section))
                        .build())
                .build();

        SendMessageRequest request = SendMessageRequest.builder()
                .to(to)
                .type("interactive")
                .interactive(interactive)
                .build();

        return sendMessage(request);
    }

    /**
     * Send a message requesting media upload
     */
    public Mono<SendMessageResponse> sendMediaRequestMessage(String to, String reportType) {
        String message = String.format(
                "Please upload your %s.\n\n" +
                "You can send it as:\n" +
                "• 📷 Image (photo of the report)\n" +
                "• 📄 PDF document\n\n" +
                "Simply attach the file in this chat.",
                reportType
        );
        return sendTextMessage(to, message);
    }

    /**
     * Get media URL from media ID
     */
    public Mono<MediaUrlResponse> getMediaUrl(String mediaId) {
        return whatsAppWebClient.get()
                .uri("/{mediaId}", mediaId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new WhatsAppApiException(
                                                "Failed to get media URL: " + body,
                                                response.statusCode().value(),
                                                "MEDIA_URL_ERROR"
                                        )
                                ))
                )
                .bodyToMono(MediaUrlResponse.class)
                .doOnSuccess(resp -> log.debug("Got media URL for ID: {}", mediaId))
                .doOnError(e -> log.error("Failed to get media URL for ID: {}", mediaId, e));
    }

    /**
     * Download media content from URL
     */
    public Mono<byte[]> downloadMedia(String mediaUrl) {
        return WebClient.create()
                .get()
                .uri(mediaUrl)
                .header("Authorization", "Bearer " + whatsAppConfig.getAccessToken())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(
                                        new MediaDownloadException("HTTP " + response.statusCode() + ": " + body)
                                ))
                )
                .bodyToMono(byte[].class)
                .doOnSuccess(bytes -> log.debug("Downloaded media: {} bytes", bytes.length))
                .doOnError(e -> log.error("Failed to download media from URL", e));
    }

    /**
     * Download media by media ID (combines getMediaUrl and downloadMedia)
     */
    public Mono<MediaDownloadResult> downloadMediaById(String mediaId) {
        return getMediaUrl(mediaId)
                .flatMap(urlResponse -> downloadMedia(urlResponse.getUrl())
                        .map(content -> new MediaDownloadResult(
                                content,
                                urlResponse.getMimeType(),
                                urlResponse.getFileSize()
                        ))
                );
    }

    /**
     * Core message sending method
     */
    private Mono<SendMessageResponse> sendMessage(SendMessageRequest request) {
        return whatsAppWebClient.post()
                .uri("/{phoneNumberId}/messages", whatsAppConfig.getPhoneNumberId())
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("WhatsApp API error: {} - {}", 
                                            response.statusCode(), body);
                                    return Mono.error(new WhatsAppApiException(
                                            "WhatsApp API error: " + body,
                                            response.statusCode().value(),
                                            "SEND_MESSAGE_ERROR"
                                    ));
                                })
                )
                .bodyToMono(SendMessageResponse.class)
                .doOnSuccess(resp -> log.info("Message sent to: {}", maskNumber(request.getTo())))
                .doOnError(e -> log.error("Failed to send message to: {}", maskNumber(request.getTo()), e));
    }

    /**
     * Send pain scale selection as interactive list
     */
    public Mono<SendMessageResponse> sendPainScaleSelector(String to) {
        List<ListOption> options = Arrays.asList(
                new ListOption("pain_0", "0 - No Pain", "No pain at all"),
                new ListOption("pain_1", "1", "Very mild pain"),
                new ListOption("pain_2", "2", "Mild pain"),
                new ListOption("pain_3", "3", "Mild to moderate"),
                new ListOption("pain_4", "4", "Moderate pain"),
                new ListOption("pain_5", "5", "Moderate pain"),
                new ListOption("pain_6", "6", "Moderate to severe"),
                new ListOption("pain_7", "7", "Severe pain"),
                new ListOption("pain_8", "8", "Very severe"),
                new ListOption("pain_9", "9", "Extreme pain"),
                new ListOption("pain_10", "10 - Worst Pain", "Worst imaginable")
        );

        return sendListMessage(
                to,
                "Please select your current pain level (0-10):\n\n" +
                "0 = No pain\n" +
                "5 = Moderate pain\n" +
                "10 = Worst possible pain",
                "Select Pain Level",
                "Pain Scale",
                options
        );
    }

    /** Mask a phone number for log output: keep first 4 + last 4, redact the middle. */
    private static String maskNumber(String number) {
        if (number == null || number.length() < 8) return "***";
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }

    // =============== Helper Records ===============

    public record ButtonOption(String id, String title) {}
    
    public record ListOption(String id, String title, String description) {}
    
    public record MediaDownloadResult(byte[] content, String mimeType, Long fileSize) {}
}
