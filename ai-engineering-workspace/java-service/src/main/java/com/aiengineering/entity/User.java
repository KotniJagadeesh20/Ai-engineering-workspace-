package com.aiengineering.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(unique = true, nullable = false)
    private String githubId;       // GitHub's numeric user id, as string

    @Column(unique = true)
    private String email;

    private String username;
    private String avatarUrl;

    private Instant createdAt = Instant.now();
}
