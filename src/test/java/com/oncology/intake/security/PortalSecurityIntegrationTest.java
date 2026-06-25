package com.oncology.intake.security;

import com.oncology.intake.entity.Patient;
import com.oncology.intake.entity.Patient.ConversationState;
import com.oncology.intake.repository.PatientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Locks in the dual-filter-chain isolation between the patient portal and the
 * staff dashboard. The classic multi-chain regression this guards against:
 * reverting the staff chain to {@code .anyRequest().authenticated()} would let
 * ANY authenticated session — including a patient's — read the doctor
 * dashboard, because the session holds one Authentication regardless of which
 * chain created it. These tests fail loudly if that ever happens again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Portal vs dashboard — role isolation")
class PortalSecurityIntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private PatientRepository patientRepository;

    /** A real patient row + matching principal, unique per call (shared H2). */
    private PatientPortalPrincipal patientPrincipal() {
        String phone = "9198" + (100000000L + RANDOM.nextInt(900000000));
        Patient patient = patientRepository.save(Patient.builder()
                .whatsappNumber(phone)
                .name("Test Patient")
                .conversationState(ConversationState.INITIAL)
                .build());
        return new PatientPortalPrincipal(
                UUID.randomUUID(), patient.getId(), phone, "Test Patient", "{noop}x", true);
    }

    // ── Anonymous ───────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous /portal redirects to the portal login")
    void anonymousPortalRedirects() throws Exception {
        mockMvc.perform(get("/portal"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/portal/login"));
    }

    @Test
    @DisplayName("anonymous /dashboard redirects to the staff login")
    void anonymousDashboardRedirects() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/dashboard/login"));
    }

    @Test
    @DisplayName("portal login/register pages are public")
    void portalPublicPages() throws Exception {
        mockMvc.perform(get("/portal/login")).andExpect(status().isOk());
        mockMvc.perform(get("/portal/register")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("portal registration POST without a CSRF token is rejected")
    void registerRequiresCsrf() throws Exception {
        mockMvc.perform(post("/portal/register")
                        .param("name", "X").param("phone", "+919800000000").param("password", "password1"))
                .andExpect(status().isForbidden());
    }

    // ── Patient session ─────────────────────────────────────────────────

    @Test
    @DisplayName("patient session can open the portal home")
    void patientCanOpenPortal() throws Exception {
        mockMvc.perform(get("/portal").with(user(patientPrincipal())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("patient session is FORBIDDEN on the staff dashboard")
    void patientCannotOpenDashboard() throws Exception {
        PatientPortalPrincipal patient = patientPrincipal();
        mockMvc.perform(get("/dashboard").with(user(patient)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard/patients").with(user(patient)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard/reports/phi-review").with(user(patient)))
                .andExpect(status().isForbidden());
        // And the catch-all (actuator metrics etc.) is staff-only too.
        mockMvc.perform(get("/actuator/metrics").with(user(patient)))
                .andExpect(status().isForbidden());
    }

    // ── Staff session ───────────────────────────────────────────────────

    @Test
    @DisplayName("doctor session is FORBIDDEN on the patient portal")
    void doctorCannotOpenPortal() throws Exception {
        mockMvc.perform(get("/portal").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portal/messages").with(user("medonc").roles("MEDICAL_ONCOLOGY")))
                .andExpect(status().isForbidden());
    }

    // ── Capability gates (PR: doctor capabilities) ──────────────────────
    // A plain staff doctor (ROLE_STAFF, no extra capabilities) is rejected at the
    // filter on capability-gated routes — proving intake/finalize/admin are gated
    // by capability, not just "is staff".

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff() {
        return user("doc").authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    @Test
    @DisplayName("staff without CAN_INTAKE is FORBIDDEN on add-patient")
    void intakeGateDenies() throws Exception {
        mockMvc.perform(get("/dashboard/patients/add").with(staff()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff without ROLE_ADMIN is FORBIDDEN on doctor management")
    void adminGateDenies() throws Exception {
        mockMvc.perform(get("/dashboard/doctors").with(staff()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff without CAN_FINALIZE is FORBIDDEN on protocol approve")
    void finalizeGateDenies() throws Exception {
        mockMvc.perform(post("/dashboard/protocol/{id}/approve", UUID.randomUUID())
                        .with(staff()).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
