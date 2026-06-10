package com.oncology.intake.repository;

import com.oncology.intake.entity.PatientAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for patient portal accounts.
 *
 * Lookups go through {@code phone_hash} (HMAC-SHA256 of the normalised phone
 * number) because the plaintext phone column is AES-GCM encrypted with a
 * random IV per write — direct equality on it never matches. Callers hash via
 * {@link com.oncology.intake.security.WhatsAppNumberHasher#hash(String)}.
 */
@Repository
public interface PatientAccountRepository extends JpaRepository<PatientAccount, UUID> {

    Optional<PatientAccount> findByPhoneHash(String phoneHash);

    boolean existsByPhoneHash(String phoneHash);

    Optional<PatientAccount> findByPatientId(UUID patientId);
}
