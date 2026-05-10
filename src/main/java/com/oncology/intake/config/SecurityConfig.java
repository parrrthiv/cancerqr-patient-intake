package com.oncology.intake.config;

import com.oncology.intake.security.DoctorUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
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
 * Spring Security configuration.
 *
 * Auth model:
 *  - The dashboard is locked behind form login backed by {@link DoctorUserDetailsService}.
 *    Spring Security does the auth check before any controller runs — adding a new
 *    /dashboard/* route does NOT need a manual session check; it's protected by default.
 *  - Public surfaces (must remain reachable without login):
 *      /webhook/whatsapp/**     — Meta posts here
 *      /actuator/health, /info  — for ALB / nginx health checks
 *      /dashboard/login          — the login form itself
 *      static assets
 *  - Roles map 1:1 from {@code Doctor.PhysicianDomain}: ROLE_ADMIN, ROLE_REFERRING_DOCTOR,
 *    ROLE_MEDICAL_ONCOLOGY, ...
 *
 * Passwords:
 *  - {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()} encodes new
 *    passwords with bcrypt and accepts {@code {noop}}, {@code {bcrypt}}, etc. for
 *    verification. Legacy plaintext rows are migrated to {@code {noop}<plaintext>}
 *    by V4__prefix_legacy_passwords.sql and are auto-upgraded to {@code {bcrypt}}
 *    on the next successful login.
 *
 * CSRF:
 *  - Enabled for everything except {@code /webhook/whatsapp/**} (Meta posts JSON,
 *    can't supply our token) and {@code /admin/test/**} (dev-only test endpoint
 *    driven by curl). Every dashboard form template was audited to use
 *    {@code th:action} — Thymeleaf-Spring Security integration auto-injects
 *    the {@code _csrf} hidden input on render.
 *  - Uses {@link CsrfTokenRequestAttributeHandler} (the plain handler) instead
 *    of Spring Security 6's default XOR variant. The XOR handler can confuse
 *    Thymeleaf form rendering in MVC apps; the plain handler is the documented
 *    compat path for traditional form-based flows.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DoctorUserDetailsService doctorUserDetailsService;

    /** Comma-separated list of origins allowed to call the dashboard (CORS). */
    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Plain CSRF token handler — see class javadoc. Spring Security 6's default
        // (XorCsrfTokenRequestAttributeHandler) can mismatch with Thymeleaf-rendered
        // hidden inputs; this one matches the form-injected value byte-for-byte.
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

                // role-restricted dashboard routes
                .requestMatchers("/dashboard/doctors", "/dashboard/doctors/**")
                    .hasRole("ADMIN")
                .requestMatchers("/dashboard/protocol/*/approve")
                    .hasRole("ADMIN")
                .requestMatchers("/dashboard/patients/add")
                    .hasRole("REFERRING_DOCTOR")

                // everything else under /dashboard requires a logged-in doctor
                .requestMatchers("/dashboard/**").authenticated()

                // anything not explicitly listed: deny by default
                .anyRequest().authenticated()
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

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(s -> s.migrateSession())
                .maximumSessions(1)
            )

            .headers(headers -> headers
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
            )

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            .authenticationProvider(daoAuthenticationProvider());

        return http.build();
    }

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
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
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
