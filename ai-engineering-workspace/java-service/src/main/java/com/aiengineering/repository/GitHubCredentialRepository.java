package com.aiengineering.repository;

import com.aiengineering.entity.GitHubCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GitHubCredentialRepository extends JpaRepository<GitHubCredential, UUID> {
    Optional<GitHubCredential> findByUserId(UUID userId);
}
