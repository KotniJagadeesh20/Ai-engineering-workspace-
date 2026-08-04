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

    private String name;
    private String owner;

    @Column(nullable = false)
    private String localPath;

    @Enumerated(EnumType.STRING)
    private IndexStatus status = IndexStatus.PENDING;

    @Column(nullable = false)
    private UUID connectedByUserId;

    // Reserved seam for future multi-tenancy (Phase 5). Not enforced with
    // permission checks yet - deliberately kept simple for Phase 1.
    private UUID workspaceId;

    private Instant createdAt = Instant.now();
}
