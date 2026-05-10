package com.oncology.intake.security;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security UserDetails wrapper for a Doctor entity.
 *
 * Authorities are derived from the doctor's PhysicianDomain. Each principal
 * carries one role: ROLE_ADMIN, ROLE_REFERRING_DOCTOR, ROLE_MEDICAL_ONCOLOGY,
 * ... matching the enum names. SecurityConfig and any future @PreAuthorize
 * annotations should reference these.
 *
 * Intentionally a snapshot (not a JPA proxy) so it can live in the security
 * context across the request without forcing a session.
 */
@RequiredArgsConstructor
@Getter
public class DoctorPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String password;
    private final String fullName;
    private final PhysicianDomain domain;
    private final boolean active;

    public static DoctorPrincipal from(Doctor d) {
        return new DoctorPrincipal(
                d.getId(),
                d.getUsername(),
                d.getPassword(),
                d.getFullName(),
                d.getDomain(),
                Boolean.TRUE.equals(d.getActive())
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + domain.name()));
    }

    @Override public boolean isAccountNonExpired()     { return active; }
    @Override public boolean isAccountNonLocked()      { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return active; }
}
