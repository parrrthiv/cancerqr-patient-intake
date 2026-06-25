package com.oncology.intake.config;

import com.oncology.intake.security.DoctorUserDetailsService;
import com.oncology.intake.security.PatientAccountDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration — TWO independent filter chains:
 *
 * <pre>
 *   Order 1:  /portal/**   — PATIENT-facing portal. Form login backed by
 *                            {@link PatientAccountDetailsService}; principal is
 *                            PatientPortalPrincipal with the single role
 *                            ROLE_PATIENT. Public: /portal/login, /portal/register,
 *                            /portal/verify (registration + WhatsApp-OTP step).
 *   Order 2:  everything   — staff dashboard + webhook + actuator. Form login
 *                            backed by {@link DoctorUserDetailsService}.
 * </pre>
 *
 * <h2>Role isolation (the multi-chain pitfall, handled)</h2>
 * The HTTP session holds ONE Authentication regardless of which chain created
 * it. If the staff chain kept {@code .anyRequest().authenticated()}, a
 * logged-in PATIENT navigating to {@code /dashboard/**} would pass — Spring
 * only checks "is authenticated", not "authenticated as whom". Therefore the
 * staff chain now requires one of the {@link PhysicianDomain} roles
 * ({@code hasAnyRole(STAFF_ROLES)}) on every protected route, and the portal
 * chain requires ROLE_PATIENT. A patient session gets 403 on the dashboard; a
 * doctor session gets 403 on the portal.
 *
 * <h2>Authentication providers</h2>
 * Each chain registers exactly one DaoAuthenticationProvider (doctor vs
 * patient), so doctor credentials can never authenticate at the portal login
 * and vice versa. With two UserDetailsService beans in the context, Spring's
 * global-manager auto-configuration backs off — intentionally; there is no
 * shared AuthenticationManager bean.
 *
 * Passwords:
 *  - {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()} encodes new
 *    passwords with bcrypt and accepts {@code {noop}}, {@code {bcrypt}}, etc. for
 *    verification. Legacy plaintext doctor rows are migrated to {@code {noop}<plaintext>}
 *    by V4__prefix_legacy_passwords.sql and auto-upgrade to {@code {bcrypt}} on the
 *    next successful login. Patient accounts are bcrypt from day one.
 *
 * CSRF:
 *  - Enabled on both chains with the plain {@link CsrfTokenRequestAttributeHandler}
 *    (Spring Security 6's XOR default can mismatch Thymeleaf-rendered hidden
 *    inputs). Excluded only for {@code /webhook/whatsapp/**} (Meta posts JSON,
 *    can't supply our token — HMAC is the auth) and {@code /admin/test/**}
 *    (dev-only, curl-driven). Every form template uses {@code th:action}, which
 *    auto-injects the {@code _csrf} hidden input on render.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DoctorUserDetailsService doctorUserDetailsService;
    private final PatientAccountDetailsService patientAccountDetailsService;

    /** Comma-separated list of origins allowed to call the dashboard (CORS). */
    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    // ── Chain 1: patient portal ─────────────────────────────────────────

    @Bean
    @Order(1)
    public SecurityFilterChain portalFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        http
            .securityMatcher("/portal/**")

            .csrf(csrf -> csrf.csrfTokenRequestHandler(csrfHandler))

            .authorizeHttpRequests(auth -> auth
                // public: login page/POST, registration, and the OTP verify step
                .requestMatchers("/portal/login", "/portal/register", "/portal/verify").permitAll()
                // everything else on the portal needs a PATIENT session
                .anyRequest().hasRole("PATIENT")
            )

            .formLogin(form -> form
                .loginPage("/portal/login")
                .loginProcessingUrl("/portal/login")
                .usernameParameter("phone")
                .passwordParameter("password")
                .defaultSuccessUrl("/portal", true)
                .failureUrl("/portal/login?error")
                .permitAll()
            )

            .logout(logout -> logout
                .logoutUrl("/portal/logout")
                .logoutSuccessUrl("/portal/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )

            .authenticationProvider(patientAuthenticationProvider());

        applySharedSessionPolicy(http);
        applySharedHeaders(http);

        return http.build();
    }

    // ── Chain 2: staff dashboard / webhook / actuator (catch-all) ───────

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Plain CSRF token handler — see class javadoc.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRequestHandler(csrfHandler)
                // Webhook: Meta cannot supply our CSRF token; signature verification
                // (X-Hub-Signature-256) is the auth mechanism for this endpoint.
                // /admin/test/**: dev-only (gated by @Profile("dev")), driven by curl.
                .ignoringRequestMatchers("/webhook/whatsapp/**", "/admin/test/**")
            )

            .authorizeHttpRequests(auth -> auth
                // public surfaces
                .requestMatchers("/webhook/whatsapp/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/dashboard/login").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                // sys-admin routes (account management + PHI review) stay ADMIN.
                .requestMatchers("/dashboard/doctors", "/dashboard/doctors/**")
                    .hasRole("ADMIN")
                .requestMatchers("/admin/phi/**")
                    .hasRole("ADMIN")
                .requestMatchers("/dashboard/reports/phi-review", "/dashboard/reports/*/phi-review")
                    .hasRole("ADMIN")

                // capability-gated routes (PR: doctor capabilities):
                //   intake  → CAN_INTAKE   (was REFERRING_DOCTOR-only)
                //   finalize/send protocol → CAN_FINALIZE (was ADMIN-only)
                .requestMatchers("/dashboard/patients/add")
                    .hasAuthority("CAN_INTAKE")
                .requestMatchers("/dashboard/protocol/*/approve", "/dashboard/protocol/*/send")
                    .hasAuthority("CAN_FINALIZE")

                // everything else under /dashboard requires a STAFF login —
                // NOT merely "authenticated" (a patient session is authenticated
                // too; every DoctorPrincipal carries ROLE_STAFF, patients don't).
                .requestMatchers("/dashboard/**").hasRole("STAFF")

                // anything not explicitly listed: staff only, deny patients
                .anyRequest().hasRole("STAFF")
            )

            .formLogin(form -> form
                .loginPage("/dashboard/login")
                .loginProcessingUrl("/dashboard/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/dashboard/login?error")
                .permitAll()
            )

            .logout(logout -> logout
                .logoutUrl("/dashboard/logout")
                .logoutSuccessUrl("/dashboard/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            .authenticationProvider(daoAuthenticationProvider());

        applySharedSessionPolicy(http);
        applySharedHeaders(http);

        return http.build();
    }

    // ── Shared policy fragments ─────────────────────────────────────────

    private void applySharedSessionPolicy(HttpSecurity http) throws Exception {
        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(s -> s.migrateSession())
                .maximumSessions(1)
        );
    }

    private void applySharedHeaders(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; " +
                        // tailwind CDN + inline scripts/styles used by current templates
                        "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data:; " +
                        "object-src 'none'; " +
                        "frame-ancestors 'self'"))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000))
        );
    }

    // ── Beans ───────────────────────────────────────────────────────────

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(doctorUserDetailsService);
        p.setPasswordEncoder(passwordEncoder());
        // Auto-upgrade legacy {noop} rows to {bcrypt} on successful login.
        p.setUserDetailsPasswordService(doctorUserDetailsService);
        return p;
    }

    @Bean
    public DaoAuthenticationProvider patientAuthenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(patientAccountDetailsService);
        p.setPasswordEncoder(passwordEncoder());
        // No password-upgrade service: patient passwords are bcrypt from creation.
        return p;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.asList(allowedOrigins.split("\\s*,\\s*")));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Requested-With"));
        cfg.setExposedHeaders(List.of("X-Request-Id"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
