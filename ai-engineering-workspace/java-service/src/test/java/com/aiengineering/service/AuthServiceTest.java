package com.aiengineering.service;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.entity.RefreshToken;
import com.aiengineering.entity.User;
import com.aiengineering.exception.UnauthorizedException;
import com.aiengineering.repository.RefreshTokenRepository;
import com.aiengineering.repository.UserRepository;
import com.aiengineering.util.TokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        authService = new AuthService(jwtService, userRepository, refreshTokenRepository, 7);

        user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(any())).thenReturn("access");
    }

    private RefreshToken stored(String raw, boolean revoked, Instant expiresAt) {
        RefreshToken rt = new RefreshToken();
        rt.setTokenHash(TokenUtil.sha256Hex(raw));
        rt.setUserId(user.getId());
        rt.setExpiresAt(expiresAt);
        rt.setRevoked(revoked);
        when(refreshTokenRepository.findByTokenHash(rt.getTokenHash())).thenReturn(Optional.of(rt));
        return rt;
    }

    @Test
    void refreshRotatesToken() {
        RefreshToken old = stored("old", false, Instant.now().plusSeconds(60));

        AuthResponse response = authService.refresh("old");

        assertTrue(old.isRevoked());
        assertNotEquals("old", response.refreshToken());
        verify(refreshTokenRepository).save(argThat(rt -> !rt.isRevoked()
            && rt.getTokenHash().equals(TokenUtil.sha256Hex(response.refreshToken()))));
    }

    @Test
    void reusedTokenRevokesAllSessions() {
        stored("old", true, Instant.now().plusSeconds(60));
        RefreshToken other = new RefreshToken();
        other.setUserId(user.getId());
        when(refreshTokenRepository.findByUserId(user.getId())).thenReturn(List.of(other));

        assertThrows(UnauthorizedException.class, () -> authService.refresh("old"));
        assertTrue(other.isRevoked());
    }

    @Test
    void expiredTokenRejected() {
        stored("old", false, Instant.now().minusSeconds(1));
        assertThrows(UnauthorizedException.class, () -> authService.refresh("old"));
    }

    @Test
    void unknownTokenRejected() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class, () -> authService.refresh("nope"));
    }
}
