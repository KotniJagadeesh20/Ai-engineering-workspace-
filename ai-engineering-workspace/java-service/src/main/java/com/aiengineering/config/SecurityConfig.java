package com.aiengineering.config;

import com.aiengineering.filter.JwtAuthFilter;
import com.aiengineering.service.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless JWT API - no CSRF tokens, no server-side sessions.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public: starting/completing GitHub OAuth, exchanging a
                // one-time login code, and refreshing tokens (refresh has
                // its own validation logic inside AuthService).
                .requestMatchers(
                    "/auth/github/**",
                    "/auth/exchange",
                    "/auth/refresh",
                    "/actuator/health"
                ).permitAll()
                // Internal service-to-service endpoints (Python -> Java).
                // NOT protected by user JWTs - gated by a shared secret
                // checked manually inside the controller instead. See
                // InternalRepoController.
                .requestMatchers("/internal/**").permitAll()
                // Everything else requires a valid access token.
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
