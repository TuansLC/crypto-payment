package com.cryptopayment.common.security.filter;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cryptopayment.common.security.jwt.AuthenticatedUser;
import com.cryptopayment.common.security.jwt.JwtService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đọc header Authorization: Bearer, verify JWT, set SecurityContext.
 * Token sai/thiếu → để request đi tiếp KHÔNG authenticated; việc từ chối (401)
 * do SecurityFilterChain + JwtAuthenticationEntryPoint xử lý (dựa trên rule của từng service).
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                AuthenticatedUser user = jwtService.parseAndValidate(token);
                // Không RBAC → authorities rỗng; authenticated=true là đủ cho .authenticated()
                var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                // CHỈ bắt lỗi liên quan JWT (hết hạn/sai chữ ký/malformed/token rỗng).
                // Lỗi khác (bug thật) sẽ propagate lên thay vì bị nuốt thành "invalid JWT".
                log.debug("JWT không hợp lệ: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
