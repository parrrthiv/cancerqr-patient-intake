package com.oncology.intake.security;

import com.oncology.intake.entity.PatientAccount;
import com.oncology.intake.repository.PatientAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads patient portal accounts for Spring Security authentication.
 *
 * <p>The submitted "username" is the patient's phone number in whatever format
 * they typed it; it is normalised + HMAC-hashed before lookup so formatting
 * differences never lock a patient out (same convention as the WhatsApp
 * webhook lookup path).
 *
 * <p>Deliberately NOT a {@code UserDetailsPasswordService}: portal passwords
 * are bcrypt from day one, so there is no legacy-encoding upgrade path here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientAccountDetailsService implements UserDetailsService {

    private final PatientAccountRepository accountRepository;
    private final WhatsAppNumberHasher hasher;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String phone) {
        PatientAccount account = accountRepository.findByPhoneHash(hasher.hash(phone))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No patient account for that phone number"));
        // Disabled accounts (pending WhatsApp verification) surface as
        // DisabledException via isEnabled() — same generic login error to the
        // user, no account-existence oracle.
        return PatientPortalPrincipal.from(account);
    }
}
