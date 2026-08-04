package com.aiengineering.filter;

import com.aiengineering.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Reads the "Authorization: Bearer <token>" header, validates the JWT,
 * and - if valid - populates the SecurityContext with the user's id so
 * downstream controllers can use @AuthenticationPrincipal / SecurityContextHolder.
 *
 * This is the equivalent of a standard Spring Security auth filter, just
 * checking our own signed JWT instead of a session cookie or Basic auth header.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwtService.isValid(token)) {
                UUID userId = jwtService.extractUserId(token);

                var authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of() // no roles/authorities yet - Phase 1 is single-role
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // If invalid, we simply don't authenticate - Spring Security's
            // authorization rules (in SecurityConfig) will reject the request
            // for any endpoint that requires authentication.
        }

        filterChain.doFilter(request, response);
    }
}
