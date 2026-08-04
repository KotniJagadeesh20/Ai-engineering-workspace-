package com.aiengineering.service;

import com.aiengineering.dto.GitHubRepoSummary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

/**
 * Calls GitHub's REST API for DATA, on behalf of an already-authenticated user.
 *
 * This is deliberately a separate class from GitHubOAuthService:
 *   - GitHubOAuthService  = the auth HANDSHAKE (exchange code, fetch profile,
 *                           store the encrypted token). Runs once, at login.
 *   - GitHubService       = ongoing DATA calls using the token that's already
 *                           stored (list repos, get repo metadata, etc).
 *                           Runs any time the user browses/connects a repo.
 *
 * Every method here needs a userId so it can look up that user's decrypted
 * GitHub token via GitHubOAuthService.getDecryptedGitHubToken(userId).
 */
@Service
public class GitHubService {

    private final WebClient webClient = WebClient.create();
    private final GitHubOAuthService gitHubOAuthService;

    public GitHubService(GitHubOAuthService gitHubOAuthService) {
        this.gitHubOAuthService = gitHubOAuthService;
    }

    /**
     * Lists the repos the authenticated user has access to (their own +
     * orgs they belong to, depending on OAuth scope granted at login).
     * Used by the frontend to show a "pick a repo to connect" screen
     * BEFORE calling POST /api/repos/connect.
     */
    public List<GitHubRepoSummary> listUserRepos(UUID userId) {
        String token = gitHubOAuthService.getDecryptedGitHubToken(userId);

        return webClient.get()
            .uri("https://api.github.com/user/repos?per_page=100&sort=updated")
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .bodyToFlux(GitHubRepoSummary.class)
            .collectList()
            .block();
    }

    /**
     * Fetches metadata for a single repo by owner/name - useful for
     * validating a repo exists and is accessible before attempting to clone it.
     */
    public GitHubRepoSummary getRepoMetadata(UUID userId, String owner, String repoName) {
        String token = gitHubOAuthService.getDecryptedGitHubToken(userId);

        return webClient.get()
            .uri("https://api.github.com/repos/" + owner + "/" + repoName)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .bodyToMono(GitHubRepoSummary.class)
            .block();
    }
}
