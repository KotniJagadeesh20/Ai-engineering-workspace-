package com.aiengineering.controller;

import com.aiengineering.dto.GitHubRepoSummary;
import com.aiengineering.dto.QueryRequest;
import com.aiengineering.dto.QueryResponse;
import com.aiengineering.dto.RepoConnectRequest;
import com.aiengineering.entity.IndexStatus;
import com.aiengineering.entity.Repo;
import com.aiengineering.exception.ResourceNotFoundException;
import com.aiengineering.repository.RepoRepository;
import com.aiengineering.service.GitHubService;
import com.aiengineering.service.IndexingClient;
import com.aiengineering.service.RagQueryClient;
import com.aiengineering.service.RepoCloneService;
import com.aiengineering.util.CurrentUserUtil;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoCloneService repoCloneService;
    private final IndexingClient indexingClient;
    private final RepoRepository repoRepository;
    private final GitHubService gitHubService;
    private final RagQueryClient ragQueryClient;

    public RepoController(
        RepoCloneService repoCloneService,
        IndexingClient indexingClient,
        RepoRepository repoRepository,
        GitHubService gitHubService,
        RagQueryClient ragQueryClient
    ) {
        this.repoCloneService = repoCloneService;
        this.indexingClient = indexingClient;
        this.repoRepository = repoRepository;
        this.gitHubService = gitHubService;
        this.ragQueryClient = ragQueryClient;
    }

    /**
     * Lets the frontend show a "pick a repo" screen BEFORE calling /connect -
     * lists what's actually available on GitHub for this user, rather than
     * making them paste a URL blind. Uses GitHubService (data calls), not
     * GitHubOAuthService (which only handles the auth handshake).
     */
    @GetMapping("/github/available")
    public ResponseEntity<List<GitHubRepoSummary>> listAvailableGitHubRepos() {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        return ResponseEntity.ok(gitHubService.listUserRepos(currentUserId));
    }

    @PostMapping("/connect")
    public ResponseEntity<Repo> connectRepo(@Valid @RequestBody RepoConnectRequest request) {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();

        Repo repo = repoCloneService.cloneAndSave(request.githubUrl(), currentUserId);
        indexingClient.triggerIndex(repo); // async hand-off to the Python service

        return ResponseEntity.ok(repo);
    }

    @GetMapping
    public ResponseEntity<List<Repo>> listMyRepos() {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        return ResponseEntity.ok(repoRepository.findByConnectedByUserId(currentUserId));
    }

    /**
     * Every single-repo lookup below uses findByIdAndConnectedByUserId, not
     * findById - this is the fix for the gap where User A could read User B's
     * repo just by knowing/guessing its UUID. A repo that exists but belongs
     * to someone else surfaces as ResourceNotFoundException (404), the same
     * as a repo that doesn't exist at all - deliberately, so a caller can't
     * use the response to tell the difference between "not yours" and
     * "doesn't exist."
     */
    @GetMapping("/{id}")
    public ResponseEntity<Repo> getRepo(@PathVariable UUID id) {
        Repo repo = findOwnedRepoOrThrow(id);
        return ResponseEntity.ok(repo);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<String> getStatus(@PathVariable UUID id) {
        Repo repo = findOwnedRepoOrThrow(id);
        return ResponseEntity.ok(repo.getStatus().name());
    }

    /**
     * Ask a question about an indexed repo. Java validates ownership here
     * BEFORE ever calling Python's /rag/query, which has no auth of its own
     * by design (see python-service/README.md).
     *
     * Phase 1 permission model is intentionally simple: only the user who
     * connected the repo can query it. Real RBAC (workspace members, shared
     * access) comes in Phase 5.
     */
    @PostMapping("/{id}/query")
    public ResponseEntity<QueryResponse> queryRepo(
        @PathVariable UUID id,
        @Valid @RequestBody QueryRequest request
    ) {
        Repo repo = findOwnedRepoOrThrow(id);

        if (repo.getStatus() != IndexStatus.READY) {
            throw new IllegalStateException(
                "Repo is not ready for questions yet (status: " + repo.getStatus() + ")");
        }

        return ResponseEntity.ok(ragQueryClient.query(id, request.question()));
    }

    private Repo findOwnedRepoOrThrow(UUID repoId) {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        return repoRepository.findByIdAndConnectedByUserId(repoId, currentUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Repo not found: " + repoId));
    }
}
