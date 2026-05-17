package com.oncology.intake.security;

import com.oncology.intake.entity.Patient;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JPA entity listener that keeps {@code Patient.whatsappNumberHash} in sync
 * with {@code Patient.whatsappNumber} on every persist and update.
 *
 * <p>Why a listener instead of explicit calls at each save site? Because
 * patient rows are created and updated in many places (the WhatsApp flow,
 * the referring-doctor form, dev seed, admin test endpoint). Centralising
 * the invariant "hash is always derived from the current plaintext number"
 * here means a new save site can't forget to set it. The listener is
 * configured via {@code @EntityListeners(PatientHashListener.class)} on the
 * {@link Patient} entity.
 *
 * <p>Spring Boot 3 + Hibernate 6 use {@code SpringBeanContainer} to
 * instantiate entity listeners, which is why constructor injection works
 * here (in plain JPA, listeners must have a no-arg constructor and look up
 * collaborators statically).
 *
 * <p>The hash is computed from the plaintext value of {@code whatsappNumber}
 * <em>before</em> Hibernate's {@code @Convert} hook encrypts it. So both
 * columns end up correctly populated in the resulting INSERT/UPDATE.
 */
@Component
@RequiredArgsConstructor
public class PatientHashListener {

    private final WhatsAppNumberHasher hasher;

    @PrePersist
    @PreUpdate
    public void computeHash(Patient patient) {
        if (patient.getWhatsappNumber() != null) {
            patient.setWhatsappNumberHash(hasher.hash(patient.getWhatsappNumber()));
        }
    }
}
