package com.premier.config;

import com.premier.admin.security.AdminAuthFilter;
import com.premier.driver.security.DriverJwtAuthFilter;
import com.premier.security.JwtAuthFilter;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter       jwtAuthFilter;
    private final AdminAuthFilter     adminAuthFilter;
    private final DriverJwtAuthFilter driverJwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          AdminAuthFilter adminAuthFilter,
                          DriverJwtAuthFilter driverJwtAuthFilter) {
        this.jwtAuthFilter       = jwtAuthFilter;
        this.adminAuthFilter     = adminAuthFilter;
        this.driverJwtAuthFilter = driverJwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(
                corsConfigurationSource()))
            .sessionManagement(session -> session
                .sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                //Public endpoints
                .requestMatchers(
                    // Passenger public
                    "/api/passenger/auth/register",
                    "/api/passenger/auth/login",
                    "/api/passenger/auth/verify-totp",
                    "/api/passenger/auth/totp/setup",
                    "/api/passenger/topup/webhook",
                    "/api/passenger/topup/check-paid",  
                    "/api/passenger/topup/check-paid/**",
                    // Admin public
                    "/api/admin/auth/**",
                    "/api/admin/auth/generate-hash",
                    // Driver public
                    "/api/driver/login",
                    "/api/driver/buses",
                    "/api/driver/bus-alerts",
                    "/api/driver/vehicles",
                    "/api/driver/drivers",
                    "/api/driver/alerts",
                    "/api/driver/gps",
                    "/api/driver/emergency/**",
                    // WebSocket
                    "/ws/**",
                    // GPS
                    "/api/driver/location",
                    "/api/driver/live-locations",
                    "/api/driver/shift-history/**",
                    // RFID Terminal
                    "/api/rfid/**",
                    "/api/auth/**"
                ).permitAll()

                // Chatbot 
                .requestMatchers("/api/passenger/chat/**")
                .authenticated()              

                //Super Admin only 
                .requestMatchers(
                    "/api/admin/logs",
                    "/api/admin/logs/**",
                    "/api/admin/logs/stats",
                    "/api/admin/manage-admins",
                    "/api/admin/manage-admins/**"
                ).hasAuthority("SUPER_ADMIN")

                // General admin
                .requestMatchers("/api/admin/**")
                    .hasAnyAuthority("ADMIN", "SUPER_ADMIN")

                //Everything else requires auth 
                .anyRequest().authenticated()
            )
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
        config.setAllowedOrigins(List.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:5175",
            "http://localhost:5176",
            "http://localhost:3001",
            "http://localhost:3002",
            //vercel
            "https://premierusers.vercel.app",
            "https://premierrfid.vercel.app",
            "https://premierdriver.vercel.app",
            "https://premieradmin.vercel.app"
        ));
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
}