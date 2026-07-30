package com.cryptopayment.common.security.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.SecurityFilterChain;

import com.cryptopayment.common.security.filter.JwtAuthenticationFilter;
import com.cryptopayment.common.security.jwt.JwtProperties;
import com.cryptopayment.common.security.jwt.JwtService;
import com.cryptopayment.common.security.web.JwtAuthenticationEntryPoint;

/**
 * Auto-configuration cho common-security: cung cấp sẵn JwtService, JwtAuthenticationFilter,
 * JwtAuthenticationEntryPoint.
 * <p>
 * ⚠️ KHÔNG định nghĩa SecurityFilterChain — mỗi service tự khai (permit endpoint public
 * của mình + gắn JwtAuthenticationFilter). Ví dụ trong service:
 * <pre>{@code
 * @Bean
 * SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
 *                           JwtAuthenticationEntryPoint entryPoint) throws Exception {
 *     http.csrf(c -> c.disable())
 *         .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
 *         .authorizeHttpRequests(a -> a
 *             .requestMatchers("/api/users/register", "/api/users/login").permitAll()
 *             .anyRequest().authenticated())
 *         .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
 *         .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
 *     return http.build();
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(JwtProperties properties) {
        return new JwtService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return new JwtAuthenticationEntryPoint();
    }
}
