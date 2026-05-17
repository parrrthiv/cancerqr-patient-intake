package com.oncology.intake.controller;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Patient;
import com.oncology.intake.repository.DoctorRepository;
import com.oncology.intake.repository.PatientRepository;
import com.oncology.intake.security.PhiEncryptor;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only endpoint that re-saves every encrypted entity so its encrypted
 * columns are rewritten under the current {@link PhiEncryptor} key version.
 *
 * <p>How it works: loading each entity through Hibernate decrypts the encrypted
 * columns via the {@code AttributeConverter}s. Touching the {@code updatedAt}
 * timestamp marks the entity dirty; calling {@code save()} schedules an UPDATE;
 * the converter re-encrypts on flush — using whatever version is current.
 *
 * <p>Without {@code @DynamicUpdate} on the entities (which is Hibernate's
 * default behaviour), every UPDATE writes <em>every</em> column, which means
 * every converter fires, which means every encrypted column is re-encrypted
 * under the current key. That's the whole trick.
 *
 * <p>Safe to call repeatedly. If no rotation is in progress (only one key
 * version configured), this is a no-op semantically — rows are re-encrypted
 * under the same key.
 *
 * <p>Route is gated to ROLE_ADMIN in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/admin/phi")
@RequiredArgsConstructor
@Slf4j
public class PhiRotationController {

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final PhiEncryptor phiEncryptor;
    private final EntityManager entityManager;

    /**
     * Diagnostic: returns the key state without doing any work.
     * Useful for verifying both keys are loaded after a rotation step.
     */
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentVersion", phiEncryptor.getCurrentVersion());
        body.put("patientCount", patientRepository.count());
        body.put("doctorCount", doctorRepository.count());
        return ResponseEntity.ok(body);
    }

    /**
     * Re-encrypt every Patient and Doctor row under the current key version.
     * Returns counts. Synchronous — fine at current scale (hundreds of rows).
     */
    @PostMapping("/rotate")
    @Transactional
    public ResponseEntity<Map<String, Object>> rotate() {
        int currentVersion = phiEncryptor.getCurrentVersion();
        log.info("Starting PHI key rotation sweep. Current write version: v{}", currentVersion);

        int patientsRotated = touchAndFlush(patientRepository.findAll());
        int doctorsRotated = touchAndFlush(doctorRepository.findAll());

        log.info("PHI rotation complete: {} patients, {} doctors re-encrypted under v{}",
                patientsRotated, doctorsRotated, currentVersion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersion", currentVersion);
        result.put("patientsRotated", patientsRotated);
        result.put("doctorsRotated", doctorsRotated);
        return ResponseEntity.ok(result);
    }

    private int touchAndFlush(Iterable<? extends Object> entities) {
        int count = 0;
        for (Object entity : entities) {
            if (entity instanceof Patient p) {
                p.setUpdatedAt(LocalDateTime.now());
                patientRepository.save(p);
            } else if (entity instanceof Doctor d) {
                d.setUpdatedAt(LocalDateTime.now());
                doctorRepository.save(d);
            }
            count++;
            // Periodic flush+clear bounds memory if the table is large.
            if (count % 100 == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        return count;
    }
}
