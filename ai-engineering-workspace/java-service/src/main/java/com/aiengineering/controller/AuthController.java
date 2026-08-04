package com.aiengineering.controller;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.dto.ExchangeCodeRequest;
import com.aiengineering.dto.RefreshRequest;
import com.aiengineering.service.AuthService;
import com.aiengineering.service.LoginCodeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Note: there's no POST /auth/login here on purpose - "login" happens as the
 * final step of the GitHub OAuth callback (see GitHubAuthController), since
 * GitHub is our only identity provider in Phase 1. This controller handles
 * what comes AFTER that: exchanging a one-time login code for real tokens,
 * and refreshing/revoking those tokens later.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginCodeService loginCodeService;

    public AuthController(AuthService authService, LoginCodeService loginCodeService) {
        this.authService = authService;
        this.loginCodeService = loginCodeService;
    }

    /**
     * Trades the short-lived, single-use code from the GitHub OAuth redirect
     * for the actual access + refresh tokens, delivered here in a JSON body
     * rather than ever appearing in a URL. See GitHubAuthController's
     * callback() for where the code is issued.
     */
    @PostMapping("/exchange")
    public ResponseEntity<AuthResponse> exchange(@Valid @RequestBody ExchangeCodeRequest request) {
        return ResponseEntity.ok(loginCodeService.consumeCode(request.code()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
