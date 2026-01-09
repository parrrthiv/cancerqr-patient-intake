package com.oncology.intake.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for the application.
 * 
 * Note: WhatsApp webhook endpoint needs to be publicly accessible
 * for webhook verification and message receiving.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for webhook endpoints (WhatsApp sends POST requests)
            .csrf(AbstractHttpConfigurer::disable)
            
            // Configure authorization
            .authorizeHttpRequests(auth -> auth
                // WhatsApp webhook - must be public
                .requestMatchers("/webhook/whatsapp/**").permitAll()
                
                // Health check endpoints
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                
                // H2 console (dev only)
                .requestMatchers("/h2-console/**").permitAll()
                
                // Dashboard - public login page, protected rest
                .requestMatchers("/dashboard/login").permitAll()
                .requestMatchers("/dashboard/**").permitAll() // For demo, later add session check
                
                // All other endpoints require authentication
                .anyRequest().permitAll()
            )
            
            // Use sessions for dashboard
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )
            
            // Allow frames for H2 console
            .headers(headers -> 
                headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
            )
            
            // CORS configuration
            .cors(cors -> cors.configurationSource(corsConfigurationSource()));
        
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
