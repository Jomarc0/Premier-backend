package com.premier.config;

import com.premier.admin.security.AdminAuthFilter;
import com.premier.driver.security.DriverJwtAuthFilter;
import com.premier.security.DeviceAuthFilter;
import com.premier.security.JwtAuthFilter;
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
    private final DriverJwtAuthFilter driverJwtAuthFilter;
    private final DeviceAuthFilter    deviceAuthFilter;

    @Value("${ALLOWED_ORIGINS:" + AllowedOrigins.DEFAULT + "}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          AdminAuthFilter adminAuthFilter,
                          DriverJwtAuthFilter driverJwtAuthFilter,
                          DeviceAuthFilter deviceAuthFilter) {
        this.jwtAuthFilter       = jwtAuthFilter;
        this.adminAuthFilter     = adminAuthFilter;
        this.driverJwtAuthFilter = driverJwtAuthFilter;
        this.deviceAuthFilter    = deviceAuthFilter;
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
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: https:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self' https://premiertranspo.onrender.com " +
                    "https://premierusers.vercel.app https://premierrfid.vercel.app " +
                    "https://premierdriver.vercel.app https://premieradmin.vercel.app " +
                    "https://premier-staff.vercel.app; " +
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
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                //Public endpoints
                .requestMatchers(
                    // Passenger public
                    "/api/passenger/auth/register",
                    "/api/passenger/auth/login",
                    "/api/passenger/auth/verify-totp",
                    "/api/passenger/auth/totp/setup",
                    "/api/passenger/chat/message",
                    "/api/passenger/topup/webhook",
                    // Admin public
                    "/api/admin/auth/login",
                    // Driver public
                    "/api/driver/login",
                    // WebSocket
                    "/ws/**",
                    "/api/auth/**",
                    "/health",
                    "/actuator/health"
                ).permitAll()

                .requestMatchers(HttpMethod.GET, "/api/rfid/vehicles")
                    .permitAll()

                .requestMatchers(
                    "/api/rfid/tap",
                    "/api/rfid/qr/process",
                    "/api/rfid/nfc/tap",
                    "/api/rfid/driver/gps")
                    .hasAnyAuthority("DEVICE_RFID_TERMINAL", "DEVICE_VEHICLE_TERMINAL")

                .requestMatchers("/api/driver/location", "/api/driver/gps")
                    .hasAuthority("ROLE_DRIVER")

                .requestMatchers(
                    "/api/driver/shift/**",
                    "/api/driver/tap-in",
                    "/api/driver/drop-off/**",
                    "/api/driver/end-shift/**"
                ).hasAuthority("ROLE_DRIVER")

                .requestMatchers(
                    "/api/driver/buses",
                    "/api/driver/vehicles",
                    "/api/driver/drivers",
                    "/api/driver/live-locations",
                    "/api/driver/shift-history/**",
                    "/api/driver/location-history/**",
                    "/api/staff/**"
                ).hasAnyAuthority("STAFF", "ADMIN", "SUPER_ADMIN")

                //Super Admin only 
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
                .requestMatchers("/api/admin/**")
                    .hasAnyAuthority("ADMIN", "SUPER_ADMIN")

                //Everything else requires auth 
                .anyRequest().authenticated()
            )
            .addFilterBefore(deviceAuthFilter,
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(adminAuthFilter,
                UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(driverJwtAuthFilter,
                AdminAuthFilter.class)
            .addFilterBefore(jwtAuthFilter,
                DriverJwtAuthFilter.class);

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
