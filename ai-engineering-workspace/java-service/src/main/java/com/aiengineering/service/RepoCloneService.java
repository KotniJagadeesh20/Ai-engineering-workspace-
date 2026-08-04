package com.aiengineering.service;

import com.aiengineering.entity.IndexStatus;
import com.aiengineering.entity.Repo;
import com.aiengineering.repository.RepoRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.UUID;

/**
 * Clones a GitHub repo to local disk using JGit (pure Java - no shelling out
 * to a `git` binary, no process management, no shell-arg escaping to worry about).
 */
@Service
public class RepoCloneService {

    private final RepoRepository repoRepository;
    private final GitHubOAuthService gitHubOAuthService;
    private final String repoStoragePath;

    public RepoCloneService(
        RepoRepository repoRepository,
        GitHubOAuthService gitHubOAuthService,
        @Value("${app.repo-storage-path}") String repoStoragePath
    ) {
        this.repoRepository = repoRepository;
        this.gitHubOAuthService = gitHubOAuthService;
        this.repoStoragePath = repoStoragePath;
    }

    public Repo cloneAndSave(String githubUrl, UUID connectedByUserId) {
        String localPath = repoStoragePath + "/" + UUID.randomUUID();

        Repo repo = new Repo();
        repo.setGithubUrl(githubUrl);
        repo.setLocalPath(localPath);
        repo.setConnectedByUserId(connectedByUserId);
        repo.setStatus(IndexStatus.CLONING);
        repo.setName(extractRepoName(githubUrl));
        repo.setOwner(extractOwner(githubUrl));
        repo = repoRepository.save(repo);

        try {
            String githubToken = gitHubOAuthService.getDecryptedGitHubToken(connectedByUserId);

            Git.cloneRepository()
                .setURI(githubUrl)
                .setDirectory(new File(localPath))
                // GitHub accepts the OAuth token as the "password" with any username
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                .call()
                .close();

            repo.setStatus(IndexStatus.PENDING); // cloned; awaiting Python indexing
            return repoRepository.save(repo);

        } catch (Exception e) {
            repo.setStatus(IndexStatus.FAILED);
            repoRepository.save(repo);
            throw new IllegalStateException("Failed to clone repository: " + githubUrl, e);
        }
    }

    private String extractRepoName(String githubUrl) {
        String cleaned = githubUrl.replaceAll("\\.git$", "");
        String[] parts = cleaned.split("/");
        return parts[parts.length - 1];
    }

    private String extractOwner(String githubUrl) {
        String cleaned = githubUrl.replaceAll("\\.git$", "");
        String[] parts = cleaned.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "unknown";
    }
}
