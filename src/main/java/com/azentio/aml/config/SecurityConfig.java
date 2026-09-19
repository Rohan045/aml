package com.azentio.aml.config;

import com.azentio.aml.security.SentinelUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API-layer RBAC (not UI-only), enforced here for coarse URL rules and with {@code @PreAuthorize}
 * on individual handlers for anything finer.
 *
 * <p>Role model:
 *
 * <ul>
 *   <li>{@code ANALYST} - works the alert queue and cases, sees masked PII only.
 *   <li>{@code SENIOR_ANALYST} - as analyst, plus full PII and case closure.
 *   <li>{@code COMPLIANCE_OFFICER} - additionally tunes rules and files SARs.
 *   <li>{@code AUDITOR} - read-only across everything, including the audit trail.
 *   <li>{@code ADMIN} - operational administration and ingestion.
 * </ul>
 *
 * <p>Sessions are stateless and HTTP Basic is used for the prototype: there is no browser session
 * to fix or forge, which is also why CSRF protection is disabled without weakening the API.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/api-docs/**",
        "/api/v1/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, SentinelUserDetailsService userDetailsService) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());

        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(provider)
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        // Rule tuning is a compliance-governance action.
                                        .requestMatchers("/api/v1/rules/**")
                                        .hasAnyRole("COMPLIANCE_OFFICER", "ADMIN", "AUDITOR")
                                        // Watchlist maintenance changes what the engine alerts on.
                                        .requestMatchers("/api/v1/watchlist/**")
                                        .hasAnyRole("COMPLIANCE_OFFICER", "ADMIN", "AUDITOR")
                                        // Ingestion is an operational, not an analyst, capability.
                                        .requestMatchers("/api/v1/ingestion/**")
                                        .hasAnyRole("ADMIN", "COMPLIANCE_OFFICER")
                                        // The audit trail is evidence; it is readable, never
                                        // writable, over the API.
                                        .requestMatchers("/api/v1/audit/**")
                                        .hasAnyRole("AUDITOR", "COMPLIANCE_OFFICER", "ADMIN")
                                        .requestMatchers("/actuator/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(
                                                (request, response, ex) ->
                                                        writeError(
                                                                response,
                                                                HttpStatus.UNAUTHORIZED,
                                                                "Authentication required",
                                                                request.getRequestURI()))
                                        .accessDeniedHandler(
                                                (request, response, ex) ->
                                                        writeError(
                                                                response,
                                                                HttpStatus.FORBIDDEN,
                                                                "Your role is not permitted to"
                                                                        + " perform this operation",
                                                                request.getRequestURI())));
        return http.build();
    }

    /**
     * Strength 12 matches the hashes seeded by the V2 migration; lowering it would silently
     * invalidate those accounts.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            org.springframework.security.config.annotation.authentication.configuration
                            .AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /** Permits a separately hosted analyst dashboard to call the API during the demo. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOriginPattern("*");
        configuration.addAllowedHeader("*");
        configuration.setAllowedMethods(
                java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** Security-filter rejections bypass the controller advice, so the shape is matched here. */
    private static void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            HttpStatus status,
            String detail,
            String path)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"timestamp":"%s","status":%d,"error":"%s","detail":"%s","path":"%s"}"""
                                .formatted(
                                        java.time.Instant.now(),
                                        status.value(),
                                        status.getReasonPhrase(),
                                        detail,
                                        path));
    }
}
