package com.oncology.intake.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Fire-and-forget wrapper for {@link ReportDataExtractionService}.
 *
 * <p>Sits in front of the synchronous {@code extractAndStoreReportData} so that
 * its long-running PDF parsing doesn't block whichever request thread happens
 * to trigger it (typically a fresh upload or the first dashboard view of a
 * newly-uploaded report).
 *
 * <h2>Why a separate bean</h2>
 * Spring's {@code @Async} proxy fires only on cross-bean invocations. If the
 * existing {@code @Transactional} method on
 * {@link ReportDataExtractionService} added {@code @Async} to itself,
 * self-invocations within that bean would skip the proxy and silently run
 * synchronously inside the caller's thread. Calling from a separate bean
 * guarantees the proxy fires for both annotations.
 *
 * <h2>Error policy</h2>
 * Async extraction failures are caught and logged. Patient state remains
 * usable: extracted columns stay null, the next dashboard view triggers
 * another async attempt. Pathological failure is not silent — the
 * {@code async.errors} Micrometer counter (wired by {@code AsyncConfig})
 * increments on any uncaught exception that escapes here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDataExtractionAsyncRunner {

    private final ReportDataExtractionService extractionService;

    @Async
    public void runForPatient(UUID patientId) {
        try {
            extractionService.extractAndStoreReportData(patientId);
        } catch (Exception e) {
            log.warn("Async report extraction failed for patient {}: {}",
                    patientId, e.getMessage());
            // Don't rethrow — async exceptions are also caught by
            // AsyncUncaughtExceptionHandler in AsyncConfig, but a caught + logged
            // failure here keeps the counter clean for "we tried, it didn't work"
            // vs "unhandled exception".
        }
    }
}
