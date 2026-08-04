package com.aiengineering.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores the user's GitHub OAuth access token, ENCRYPTED at rest.
 * This is deliberately a separate entity from RefreshToken:
 *  - RefreshToken belongs to OUR auth system (frontend <-> our backend)
 *  - GitHubCredential belongs to the GitHub integration (our backend <-> GitHub API)
 * Different lifecycle, different revocation rules, different secret material.
 */
@Entity
@Table(name = "github_credentials")
@Getter
@Setter
@NoArgsConstructor
public class GitHubCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    // GitHub OAuth tokens (classic) don't expire by default, but GitHub Apps
    // tokens do - keep this so the encryption/refresh path is ready either way.
    private Instant expiresAt;

    private Instant updatedAt = Instant.now();
}
