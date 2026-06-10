package com.oncology.intake.security;

import com.oncology.intake.entity.PatientAccount;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security UserDetails for a patient portal account.
 *
 * <p>Carries exactly one authority: {@code ROLE_PATIENT}. The portal filter
 * chain requires this role for everything under {@code /portal/**}, and the
 * staff chain requires one of the {@code PhysicianDomain} roles — so a patient
 * session can never read the doctor dashboard and vice versa.
 *
 * <p>Snapshot (not a JPA proxy) so it can live in the HTTP session safely.
 * Controllers must use {@link #getPatientId()} as the ONLY patient selector —
 * never a patient id from the request — which makes cross-patient IDOR
 * structurally impossible on the portal.
 */
@RequiredArgsConstructor
@Getter
public class PatientPortalPrincipal implements UserDetails {

    private final UUID accountId;
    private final UUID patientId;
    /** Normalised phone number (the login identifier). */
    private final String phone;
    private final String displayName;
    private final String password;
    private final boolean enabled;

    public static PatientPortalPrincipal from(PatientAccount a) {
        return new PatientPortalPrincipal(
                a.getId(),
                a.getPatientId(),
                a.getPhone(),
                a.getDisplayName(),
                a.getPassword(),
                Boolean.TRUE.equals(a.getEnabled())
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }

    /** Login name shown to Spring Security; the normalised phone number. */
    @Override
    public String getUsername() {
        return phone;
    }

    @Override public boolean isAccountNonExpired()     { return enabled; }
    @Override public boolean isAccountNonLocked()      { return enabled; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled; }
}
