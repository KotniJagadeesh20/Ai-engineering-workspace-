package com.aiengineering.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repos")
@Getter
@Setter
@NoArgsConstructor
public class Repo {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String githubUrl;

    // Source-control provider; only "github" today.
    @Column(nullable = false, length = 32)
    private String provider = "github";

    private String name;
    private String owner;

    // Server-side filesystem path - internal, never serialized to clients.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(nullable = false)
    private String localPath;

    @Enumerated(EnumType.STRING)
    private IndexStatus status = IndexStatus.PENDING;

    // Populated when status == FAILED (reported by the Python service).
    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private UUID connectedByUserId;

    // Reserved seam for future multi-tenancy (Phase 5). Not enforced with
    // permission checks yet - deliberately kept simple for Phase 1.
    private UUID workspaceId;

    private Instant createdAt = Instant.now();
}
