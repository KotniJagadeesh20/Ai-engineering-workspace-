package com.aiengineering.service;

import com.aiengineering.dto.GitHubTokenResponse;
import com.aiengineering.dto.GitHubUserProfile;
import com.aiengineering.entity.GitHubCredential;
import com.aiengineering.entity.User;
import com.aiengineering.repository.GitHubCredentialRepository;
import com.aiengineering.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;

/**
 * Handles the GitHub OAuth "Authorization Code" flow end to end:
 *   1. buildAuthorizationUrl()  - where we redirect the browser to start login
 *   2. handleCallback(code)    - exchanges the code for a GitHub token,
 *                                 fetches the profile, upserts our User,
 *                                 stores the GitHub token encrypted.
 *
 * IMPORTANT: this GitHub access token is NOT our JWT. It's what WE use later
 * to call the GitHub API on the user's behalf (clone private repos, open PRs,
 * etc in later phases). It's stored encrypted via EncryptionService.
 *
 * ARCHITECTURAL NOTE - this class currently does double duty as both
 * IDENTITY (who is this person - upsertUser) and INTEGRATION (what can we
 * do on GitHub on their behalf - storeGitHubCredential). That's a deliberate
 * simplification, not an oversight: GitHub is currently the ONLY way to
 * become a platform user at all, so there's no meaningful distinction yet
 * between "log in" and "connect GitHub" - they're the same event. If a
 * second identity provider or a second source-control provider (Bitbucket,
 * GitLab) is ever added, this class's two responsibilities should split -
 * see docs/future-source-control-integrations.md for the parked design of
 * what that split looks like and why it isn't done now.
 */
@Service
public class GitHubOAuthService {

    private final WebClient webClient = WebClient.create();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scope;

    private final UserRepository userRepository;
    private final GitHubCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;

    public GitHubOAuthService(
        @Value("${github.client-id}") String clientId,
        @Value("${github.client-secret}") String clientSecret,
        @Value("${github.redirect-uri}") String redirectUri,
        @Value("${github.scope}") String scope,
        UserRepository userRepository,
        GitHubCredentialRepository credentialRepository,
        EncryptionService encryptionService
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
    }

    public String buildAuthorizationUrl(String state) {
        return "https://github.com/login/oauth/authorize"
            + "?client_id=" + clientId
            + "&redirect_uri=" + redirectUri
            + "&scope=" + scope
            + "&state=" + state;
    }

    /**
     * Step 1: exchange the temporary `code` GitHub sent us for a real access token.
     */
    public GitHubTokenResponse exchangeCodeForToken(String code) {
        return webClient.post()
            .uri("https://github.com/login/oauth/access_token")
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(java.util.Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri
            ))
            .retrieve()
            .bodyToMono(GitHubTokenResponse.class)
            .block();
    }

    /**
     * Step 2: use the token to fetch the GitHub profile.
     */
    public GitHubUserProfile fetchUserProfile(String githubAccessToken) {
        return webClient.get()
            .uri("https://api.github.com/user")
            .header("Authorization", "Bearer " + githubAccessToken)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .bodyToMono(GitHubUserProfile.class)
            .block();
    }

    /**
     * Step 3: upsert our local User record from the GitHub profile.
     */
    public User upsertUser(GitHubUserProfile profile) {
        String githubId = String.valueOf(profile.id());

        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setUsername(profile.login());
        user.setEmail(profile.email());
        user.setAvatarUrl(profile.avatarUrl());

        return userRepository.save(user);
    }

    /**
     * Step 4: store the GitHub access token, ENCRYPTED, tied to our local user.
     * Separate table/entity from our own JWT refresh tokens - see GitHubCredential javadoc.
     */
    public void storeGitHubCredential(User user, GitHubTokenResponse tokenResponse) {
        GitHubCredential credential = credentialRepository.findByUserId(user.getId())
            .orElseGet(GitHubCredential::new);

        credential.setUserId(user.getId());
        credential.setEncryptedAccessToken(encryptionService.encrypt(tokenResponse.accessToken()));
        credential.setUpdatedAt(Instant.now());

        credentialRepository.save(credential);
    }

    /**
     * Retrieves the decrypted GitHub token for making API calls on the user's behalf.
     * Used later by RepoCloneService / GitHub API callers.
     */
    public String getDecryptedGitHubToken(java.util.UUID userId) {
        GitHubCredential credential = credentialRepository.findByUserId(userId)
            .orElseThrow(() -> new IllegalStateException("No GitHub credential found for user " + userId));
        return encryptionService.decrypt(credential.getEncryptedAccessToken());
    }
}
