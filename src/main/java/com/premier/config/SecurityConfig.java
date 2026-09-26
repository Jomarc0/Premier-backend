package com.premier.config;

import com.premier.admin.security.AdminAuthFilter;
import com.premier.security.DeviceAuthFilter;
import com.premier.driver.security.DriverAuthFilter;
import com.premier.security.JwtAuthFilter;
import com.premier.security.SecurityRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter       jwtAuthFilter;
    private final AdminAuthFilter     adminAuthFilter;
    private final DeviceAuthFilter    deviceAuthFilter;
    private final DriverAuthFilter    driverAuthFilter;
    private final SecurityRateLimitFilter securityRateLimitFilter;
    private final com.premier.security.AuthenticatedRateLimitFilter authenticatedRateLimitFilter;

    @Value("${ALLOWED_ORIGINS:" + AllowedOrigins.DEFAULT + "}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          AdminAuthFilter adminAuthFilter,
                          DeviceAuthFilter deviceAuthFilter,
                          DriverAuthFilter driverAuthFilter,
                          SecurityRateLimitFilter securityRateLimitFilter,
                          com.premier.security.AuthenticatedRateLimitFilter authenticatedRateLimitFilter) {
        this.jwtAuthFilter       = jwtAuthFilter;
        this.adminAuthFilter     = adminAuthFilter;
        this.deviceAuthFilter    = deviceAuthFilter;
        this.driverAuthFilter    = driverAuthFilter;
        this.securityRateLimitFilter = securityRateLimitFilter;
        this.authenticatedRateLimitFilter = authenticatedRateLimitFilter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                .contentTypeOptions(contentType -> {})
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                	    "default-src 'self'; " +
                	    "script-src 'self' https://us-assets.i.posthog.com; " +
                	    "style-src 'self' 'unsafe-inline' https://us-assets.i.posthog.com; " +
                	    "img-src 'self' data: https:; " +
                	    "font-src 'self' data:; " +
                	    "connect-src 'self' https://premiertranspo.onrender.com " +
                	    "https://premierusers.vercel.app https://premierrfid.vercel.app " +
                	    "https://premierdriver.vercel.app https://premieradmin.vercel.app " +
                	    "https://premier-staff.vercel.app https://us.i.posthog.com " +
                	    "https://us-assets.i.posthog.com; " +
                	    "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(self), payment=(self), usb=(), bluetooth=()"))
                .cacheControl(cache -> {})
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31536000)))
            .cors(cors -> cors.configurationSource(
                corsConfigurationSource()))
            .sessionManagement(session -> session
                .sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required.\"}");
            }))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                //Public endpoints
                .requestMatchers(
                    // Passenger public
                    "/api/passenger/auth/register",
                    "/api/passenger/auth/recovery/complete",
                    "/api/passenger/auth/login",
                    "/api/passenger/auth/verify-totp",
                    "/api/passenger/auth/totp/setup",
                    "/api/passenger/auth/biometric/refresh",
                    "/api/passenger/auth/biometric/revoke",
                    "/api/passenger/chat/message",
                    "/api/passenger/topup/webhook",
                    // Admin public
                    "/api/admin/auth/login",
                    // Driver public
                    "/api/driver/login",
                    // WebSocket
                    "/ws/**",
                    "/ws-native/**",
                    "/api/auth/**",
                    "/health",
                    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
                ).permitAll()

                .requestMatchers(HttpMethod.GET, "/api/rfid/vehicles")
                    .permitAll()

                .requestMatchers(
                    "/api/rfid/tap",
                    "/api/rfid/qr/process",
                    "/api/rfid/nfc/tap",
                    "/api/rfid/gps", "/api/rfid/heartbeat")
                    .hasAnyAuthority("DEVICE_RFID_TERMINAL", "DEVICE_VEHICLE_TERMINAL")

                .requestMatchers(
                    "/api/rfid/registration/uid-request",
                    "/api/rfid/registration/uid-capture")
                    .hasAnyAuthority("DEVICE_RFID_TERMINAL", "DEVICE_VEHICLE_TERMINAL")

                .requestMatchers("/api/staff/**")
                    .hasAnyAuthority("STAFF", "ADMIN", "SUPER_ADMIN")

                // Read-only operational monitoring is available to both terminal staff and admins.
                .requestMatchers(HttpMethod.GET, "/api/admin/vehicle-monitoring/**")
                    .hasAnyAuthority("STAFF", "ADMIN", "SUPER_ADMIN")

                .requestMatchers("/api/driver/**")
                    .hasAuthority("DRIVER")

                //Super Admin only 
                .requestMatchers("/api/admin/auth/totp/setup", "/api/admin/auth/totp/verify")
                    .hasAnyAuthority("ADMIN_ENROLL", "ADMIN", "SUPER_ADMIN")
                .requestMatchers(
                    "/api/admin/logs",
                    "/api/admin/logs/**",
                    "/api/admin/logs/stats",
                    "/api/admin/devices",
                    "/api/admin/devices/**",
                    "/api/admin/manage-admins",
                    "/api/admin/manage-admins/**"
                ).hasAuthority("SUPER_ADMIN")

                // General admin
                .requestMatchers(HttpMethod.POST, "/api/admin/transactions/*/reverse-fare").hasAuthority("SUPER_ADMIN")
                .requestMatchers("/api/admin/**")
                    .hasAnyAuthority("ADMIN", "SUPER_ADMIN")

                //Everything else requires auth 
                .anyRequest().authenticated()
            )
            .addFilterAfter(authenticatedRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(deviceAuthFilter,
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(securityRateLimitFilter,
                DeviceAuthFilter.class)
            .addFilterBefore(adminAuthFilter,
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(driverAuthFilter,
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter,
                AdminAuthFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(parseOrigins(allowedOrigins));
        config.setAllowedMethods(List.of(
            "GET", "POST", "PUT",
            "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> parseOrigins(String origins) {
        return AllowedOrigins.parse(origins);
    }
}

