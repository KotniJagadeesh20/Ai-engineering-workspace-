package com.aiengineering.util;

import com.aiengineering.exception.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * JwtAuthFilter sets the authenticated principal to the user's UUID
 * (see JwtAuthFilter#doFilterInternal). This util centralizes reading
 * it back out inside controllers instead of repeating the cast everywhere.
 */
public final class CurrentUserUtil {

    private CurrentUserUtil() {}

    public static UUID getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
            ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            : null;

        if (principal instanceof UUID userId) {
            return userId;
        }
        throw new UnauthorizedException("No authenticated user in context");
    }
}
