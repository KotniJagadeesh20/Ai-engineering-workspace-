package com.aiengineering.service;

import com.aiengineering.entity.IndexStatus;
import com.aiengineering.entity.Repo;
import com.aiengineering.repository.RepoRepository;
import com.aiengineering.util.GitHubUrl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.UUID;

/**
 * Clones a GitHub repo to local disk using JGit (pure Java - no shelling out
 * to a `git` binary, no process management, no shell-arg escaping to worry about).
 */
@Service
public class RepoCloneService {

    private static final Logger log = LoggerFactory.getLogger(RepoCloneService.class);

    private final RepoRepository repoRepository;
    private final IndexingClient indexingClient;
    private final GitHubOAuthService gitHubOAuthService;
    private final String repoStoragePath;

    public RepoCloneService(
        RepoRepository repoRepository,
        GitHubOAuthService gitHubOAuthService,
        IndexingClient indexingClient,
        @Value("${app.repo-storage-path}") String repoStoragePath
    ) {
        this.repoRepository = repoRepository;
        this.gitHubOAuthService = gitHubOAuthService;
        this.indexingClient = indexingClient;
        this.repoStoragePath = repoStoragePath;
    }

    /**
     * Step 1 (synchronous, fast): validates the URL and records the repo as
     * CLONING so the caller can return immediately.
     */
    public Repo createPending(String githubUrl, UUID connectedByUserId) {
        GitHubUrl parsed = GitHubUrl.parse(githubUrl); // throws IllegalArgumentException -> 400

        Repo repo = new Repo();
        repo.setGithubUrl(parsed.url());
        repo.setLocalPath(repoStoragePath + "/" + UUID.randomUUID());
        repo.setConnectedByUserId(connectedByUserId);
        repo.setStatus(IndexStatus.CLONING);
        repo.setName(parsed.name());
        repo.setOwner(parsed.owner());
        return repoRepository.save(repo);
    }

    /**
     * Step 2 (background thread): clones, then hands off to Python for
     * indexing. Progress is visible via GET /api/repos/{id}/status. Takes an
     * id, not an entity, so it works from a fresh persistence context.
     * Never throws - failures are recorded on the repo as FAILED + errorMessage.
     */
    @Async("cloneExecutor")
    public void cloneAndIndex(UUID repoId) {
        Repo repo = repoRepository.findById(repoId).orElse(null);
        if (repo == null) {
            return;
        }

        try {
            String githubToken = gitHubOAuthService.getDecryptedGitHubToken(repo.getConnectedByUserId());

            Git.cloneRepository()
                .setURI(repo.getGithubUrl())
                .setDirectory(new File(repo.getLocalPath()))
                // GitHub accepts the OAuth token as the "password" with any username
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                .call()
                .close();

            repo.setStatus(IndexStatus.PENDING); // cloned; awaiting Python indexing
            repo = repoRepository.save(repo);
        } catch (Exception e) {
            log.error("Clone failed for repo {}", repoId, e);
            deleteQuietly(repo.getLocalPath());
            markFailed(repo, "Failed to clone repository");
            return;
        }

        indexingClient.triggerIndex(repo);
    }

    public void markFailed(Repo repo, String message) {
        repo.setStatus(IndexStatus.FAILED);
        repo.setErrorMessage(message);
        repoRepository.save(repo);
    }

    private void deleteQuietly(String path) {
        try {
            org.eclipse.jgit.util.FileUtils.delete(new File(path),
                org.eclipse.jgit.util.FileUtils.RECURSIVE | org.eclipse.jgit.util.FileUtils.SKIP_MISSING);
        } catch (java.io.IOException e) {
            log.warn("Could not clean up partial clone at {}", path, e);
        }
    }
}
