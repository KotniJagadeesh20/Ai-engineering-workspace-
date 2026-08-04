package com.aiengineering.repository;

import com.aiengineering.entity.Repo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repo, UUID> {
    List<Repo> findByConnectedByUserId(UUID userId);

    // Ownership-scoped lookup - used everywhere a repo is fetched by id so
    // one user can never read/query another user's repo just by guessing or
    // observing a UUID. Returns empty (not the repo) if it exists but isn't
    // this user's - callers should treat that the same as "not found" (404,
    // not 403) so as not to confirm to a caller that a given repo_id exists
    // at all.
    Optional<Repo> findByIdAndConnectedByUserId(UUID id, UUID connectedByUserId);
}
