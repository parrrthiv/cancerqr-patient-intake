package com.oncology.intake.security;

import com.oncology.intake.entity.Doctor;
import com.oncology.intake.entity.Doctor.PhysicianDomain;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security UserDetails wrapper for a Doctor entity.
 *
 * <p>Authorities are a blend of the doctor's review specialty and their
 * capability flags (PR: doctor capabilities):
 * <ul>
 *   <li>{@code ROLE_STAFF} — always; the catch-all gate for {@code /dashboard/**}
 *       (keeps patient sessions out without enumerating every domain).</li>
 *   <li>{@code ROLE_<domain>} — the specialty/sys-admin role (e.g. {@code ROLE_ADMIN},
 *       {@code ROLE_MEDICAL_ONCOLOGY}). {@code ROLE_ADMIN} still gates doctor
 *       management + PHI review.</li>
 *   <li>{@code CAN_INTAKE} — may perform patient intake ({@code /dashboard/patients/add}).</li>
 *   <li>{@code CAN_FINALIZE} — may approve/finalize + send the final protocol.</li>
 *   <li>{@code CAN_REVIEW} — sits on the tumor board.</li>
 * </ul>
 *
 * <p>Intentionally a snapshot (not a JPA proxy) so it can live in the security
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
    private final boolean canIntake;
    private final boolean canFinalize;
    private final boolean canReview;

    public static DoctorPrincipal from(Doctor d) {
        return new DoctorPrincipal(
                d.getId(),
                d.getUsername(),
                d.getPassword(),
                d.getFullName(),
                d.getDomain(),
                Boolean.TRUE.equals(d.getActive()),
                Boolean.TRUE.equals(d.getCanIntake()),
                Boolean.TRUE.equals(d.getCanFinalize()),
                Boolean.TRUE.equals(d.getCanReview())
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_STAFF"));
        // Specialty / sys-admin role (domain==ADMIN yields ROLE_ADMIN).
        auths.add(new SimpleGrantedAuthority("ROLE_" + domain.name()));
        if (canIntake)   auths.add(new SimpleGrantedAuthority("CAN_INTAKE"));
        if (canFinalize) auths.add(new SimpleGrantedAuthority("CAN_FINALIZE"));
        if (canReview)   auths.add(new SimpleGrantedAuthority("CAN_REVIEW"));
        return auths;
    }

    @Override public boolean isAccountNonExpired()     { return active; }
    @Override public boolean isAccountNonLocked()      { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return active; }
}
