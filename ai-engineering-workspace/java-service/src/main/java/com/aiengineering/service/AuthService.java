package com.aiengineering.service;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.entity.RefreshToken;
import com.aiengineering.entity.User;
import com.aiengineering.exception.UnauthorizedException;
import com.aiengineering.repository.RefreshTokenRepository;
import com.aiengineering.repository.UserRepository;
import com.aiengineering.util.TokenUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenExpiryDays;

    public AuthService(
        JwtService jwtService,
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        @Value("${jwt.refresh-token-expiry-days}") long refreshTokenExpiryDays
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    /**
     * Called once GitHub OAuth has already resolved (or created) the local User.
     * Issues our OWN access + refresh tokens - separate from GitHub's token,
     * which is stored/encrypted elsewhere (see GitHubCredential).
     */
    public AuthResponse login(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = issueRefreshToken(user.getId());

        return new AuthResponse(accessToken, rawRefreshToken, jwtService.getAccessTokenExpirySeconds());
    }

    /**
     * Rotates the refresh token: the presented token is revoked and a new one
     * issued. If an already-revoked token is presented, it was either replayed
     * or stolen, so every session for that user is revoked.
     */
    // noRollbackFor: the revoke-all on reuse detection must commit even though we throw.
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(String refreshTokenValue) {
        String hash = TokenUtil.sha256Hex(refreshTokenValue);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        if (stored.isRevoked()) {
            refreshTokenRepository.findByUserId(stored.getUserId()).forEach(rt -> rt.setRevoked(true));
            throw new UnauthorizedException("Refresh token reuse detected - please log in again");
        }
        if (!stored.getExpiresAt().isAfter(Instant.now())) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }

        User user = userRepository.findById(stored.getUserId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));

        stored.setRevoked(true);
        String newAccessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = issueRefreshToken(user.getId());

        return new AuthResponse(newAccessToken, newRefreshToken, jwtService.getAccessTokenExpirySeconds());
    }

    public void logout(String refreshTokenValue) {
        String hash = TokenUtil.sha256Hex(refreshTokenValue);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    /**
     * Generates the raw token, persists only its hash, and returns the raw
     * value to the caller - this is the ONLY point in the system where the
     * raw refresh token exists outside the client's hands. It is never
     * written to the database or logged anywhere in this form.
     */
    private String issueRefreshToken(UUID userId) {
        String rawToken = TokenUtil.generateOpaqueToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenHash(TokenUtil.sha256Hex(rawToken));
        refreshToken.setUserId(userId);
        refreshToken.setExpiresAt(Instant.now().plus(refreshTokenExpiryDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }
}
