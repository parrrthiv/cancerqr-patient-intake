package com.oncology.intake.service;

import com.oncology.intake.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Background maintenance for WhatsApp conversations.
 *
 * <p>The intake flow defines an {@code EXPIRED} state and the repository ships
 * {@link PatientRepository#expireStaleConversations(LocalDateTime)}, but until
 * now nothing invoked it — a conversation abandoned mid-intake stayed
 * indefinitely in its last state. This scheduled sweep retires conversations
 * idle longer than {@code app.conversation-timeout-minutes} so they don't
 * linger and so the state actually means something.
 *
 * <p>Enabled by {@code @EnableScheduling} on {@link com.oncology.intake.config.AsyncConfig}.
 * State is swept in a single bulk UPDATE (no per-row load), so this is cheap
 * even with many stale rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationMaintenanceService {

    private final PatientRepository patientRepository;

    /** Minutes of inactivity after which an in-flight conversation is expired. */
    @Value("${app.conversation-timeout-minutes:60}")
    private long conversationTimeoutMinutes;

    /**
     * Expire conversations whose last interaction is older than the configured
     * timeout. Runs on a fixed delay (default 15 min) after an initial 1-minute
     * delay so it never races application startup / Flyway. Both intervals are
     * overridable via {@code app.conversation-maintenance-interval-ms} and
     * {@code app.conversation-maintenance-initial-delay-ms}.
     */
    @Scheduled(
            fixedDelayString = "${app.conversation-maintenance-interval-ms:900000}",
            initialDelayString = "${app.conversation-maintenance-initial-delay-ms:60000}")
    @Transactional
    public void expireStaleConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(conversationTimeoutMinutes);
        int expired = patientRepository.expireStaleConversations(cutoff);
        if (expired > 0) {
            log.info("Conversation maintenance: expired {} stale conversation(s) idle > {} min",
                    expired, conversationTimeoutMinutes);
        }
    }
}
