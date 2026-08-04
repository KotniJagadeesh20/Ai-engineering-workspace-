package com.aiengineering.service;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.exception.UnauthorizedException;
import com.aiengineering.util.TokenUtil;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Solves the "don't put tokens in the redirect URL" problem without relying
 * on cross-origin cookies (which get genuinely fragile when the caller is a
 * static file:// page, like our dev-tools test harness - SameSite/CORS
 * credential rules for file:// origins are inconsistent across browsers).
 *
 * Instead: the OAuth callback redirects with a single opaque, short-lived,
 * single-use CODE - not the actual tokens. The frontend then POSTs that code
 * to /auth/exchange and gets the real access+refresh tokens back in a JSON
 * response body. A code that leaks into browser history/logs is useless
 * after ~60 seconds or after one use, unlike a raw token.
 *
 * In-memory by design for Phase 1 (a single process, single instance).
 * KNOWN LIMITATION: this won't work if you ever run multiple instances of
 * this service behind a load balancer, since the code issued by instance A
 * wouldn't be visible to instance B. At that point, swap this for a shared
 * store (Redis with a short TTL is the natural fit) - the interface here
 * stays the same either way.
 */
@Service
public class LoginCodeService {

    private static final long CODE_TTL_SECONDS = 60;

    private record PendingLogin(AuthResponse authResponse, Instant expiresAt) {}

    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();

    public String issueCode(AuthResponse authResponse) {
        String code = TokenUtil.generateOpaqueToken();
        pendingLogins.put(code, new PendingLogin(authResponse, Instant.now().plusSeconds(CODE_TTL_SECONDS)));
        return code;
    }

    /**
     * Single-use: the code is removed on first read, whether or not it's
     * still valid. A second exchange attempt with the same code always fails.
     */
    public AuthResponse consumeCode(String code) {
        PendingLogin pending = pendingLogins.remove(code);

        if (pending == null) {
            throw new UnauthorizedException("Invalid or already-used login code");
        }
        if (pending.expiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Login code has expired");
        }
        return pending.authResponse();
    }
}
