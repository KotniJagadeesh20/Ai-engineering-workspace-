package com.aiengineering.controller;

import com.aiengineering.dto.IndexStatusUpdateRequest;
import com.aiengineering.entity.Repo;
import com.aiengineering.exception.ResourceNotFoundException;
import com.aiengineering.exception.UnauthorizedException;
import com.aiengineering.repository.RepoRepository;
import com.aiengineering.util.TokenUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoints meant to be called ONLY by the Python service, never by a user's
 * browser. Deliberately NOT under /api/repos - the /internal prefix makes
 * that intent obvious, and SecurityConfig permits this path without a user
 * JWT (there isn't one - Python isn't a logged-in user). Instead, every
 * method here checks a shared secret header manually.
 *
 * FAILS APPLICATION STARTUP if internal.service-secret isn't configured -
 * deliberately NOT a "warn and allow through" fallback. A missing config
 * value should never silently turn authentication off; it should stop the
 * app from starting at all. This applies even in local dev - set
 * INTERNAL_SERVICE_SECRET to any placeholder string locally (it just has to
 * match python-service's value); never leave it unset.
 */
@RestController
@RequestMapping("/internal/repos")
public class InternalRepoController {

    private static final String SECRET_HEADER = "X-Internal-Secret";

    private final RepoRepository repoRepository;
    private final String configuredSecret;

    public InternalRepoController(
        RepoRepository repoRepository,
        @Value("${internal.service-secret}") String configuredSecret
    ) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalStateException(
                "internal.service-secret (INTERNAL_SERVICE_SECRET) must be set - " +
                "refusing to start with /internal/** endpoints unprotected. " +
                "Set it to any value locally (must match python-service's value " +
                "exactly), or a real generated secret in any shared/deployed environment."
            );
        }
        this.repoRepository = repoRepository;
        this.configuredSecret = configuredSecret;
    }

    /**
     * Callback the PYTHON service hits once it finishes (or fails) indexing
     * a repo - the other half of the async hand-off IndexingClient started.
     */
    @PatchMapping("/{id}/index-status")
    public void updateIndexStatus(
        @PathVariable UUID id,
        @RequestHeader(value = SECRET_HEADER, required = false) String providedSecret,
        @RequestBody IndexStatusUpdateRequest request
    ) {
        verifyInternalSecret(providedSecret);

        Repo repo = repoRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repo not found: " + id));
        repo.setStatus(request.status());
        repoRepository.save(repo);
    }

    private void verifyInternalSecret(String providedSecret) {
        // Constant-time comparison - a plain String.equals() short-circuits on
        // the first mismatched character, which leaks (via response timing)
        // how many leading characters an attacker guessed correctly. Not a
        // huge risk for a service-to-service secret behind a private network,
        // but it's the same category of check as a password comparison and
        // costs nothing extra to do correctly.
        if (providedSecret == null || !TokenUtil.constantTimeEquals(configuredSecret, providedSecret)) {
            throw new UnauthorizedException("Invalid or missing internal service secret");
        }
    }
}
