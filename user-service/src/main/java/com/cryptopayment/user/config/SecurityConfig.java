package com.cryptopayment.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.cryptopayment.common.security.filter.JwtAuthenticationFilter;
import com.cryptopayment.common.security.web.JwtAuthenticationEntryPoint;

/**
 * SecurityFilterChain riêng của user-service (common-security KHÔNG cung cấp sẵn chain).
 * <p>
 * - Stateless (JWT), tắt CSRF (không dùng cookie/session).
 * - register/login là public; còn lại cần authenticated.
 * - Gắn {@link JwtAuthenticationFilter} (bean từ common-security auto-config) trước
 *   UsernamePasswordAuthenticationFilter; 401 trả qua {@link JwtAuthenticationEntryPoint}
 *   theo đúng format ApiResponse.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           JwtAuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/users/register", "/api/users/login").permitAll()
                        // CHỈ mở health/info cho probe của Docker/K8s.
                        // TUYỆT ĐỐI KHÔNG permit "/actuator/**" — sẽ hở cả /actuator/env
                        // (lộ datasource password, app.jwt.secret) và /actuator/heapdump.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** BCrypt cho password — strength mặc định (10). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
