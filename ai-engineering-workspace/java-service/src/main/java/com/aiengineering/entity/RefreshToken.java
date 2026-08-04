package com.aiengineering.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    // SHA-256 hash of the opaque random token - NOT the raw token, and NOT
    // a JWT. We store only the hash so a database leak doesn't hand out
    // usable sessions (same principle as hashing a password). The raw token
    // exists only transiently: generated, handed to the client once, then
    // never persisted anywhere in this form. Revocation still works by
    // flagging/deleting this row, same as before - hashing at-rest doesn't
    // change the revocation story, only what an attacker gets from a leak.
    @Column(unique = true, nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Instant expiresAt;

    private boolean revoked = false;

    private Instant createdAt = Instant.now();
}
