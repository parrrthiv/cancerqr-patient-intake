package com.oncology.intake.security;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads doctors from the database for Spring Security authentication.
 *
 * Also implements {@link UserDetailsPasswordService} so that, when
 * {@code DaoAuthenticationProvider.upgradeEncoding(...)} returns true on a
 * successful login (e.g. legacy {@code {noop}} → modern {@code {bcrypt}}),
 * the new hash is persisted automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DoctorUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final DoctorRepository doctorRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        Doctor d = doctorRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No doctor with that username"));
        return DoctorPrincipal.from(d);
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        Doctor d = doctorRepository.findByUsername(user.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException(user.getUsername()));
        d.setPassword(newPassword);
        doctorRepository.save(d);
        log.info("Password encoding upgraded for user id={}", d.getId());
        return DoctorPrincipal.from(d);
    }
}
