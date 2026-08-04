package com.aiengineering.service;

import com.aiengineering.dto.IndexRequest;
import com.aiengineering.entity.IndexStatus;
import com.aiengineering.entity.Repo;
import com.aiengineering.repository.RepoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Thin client for the Python AI service. Java never does embeddings/RAG itself -
 * it just hands off the repo location and lets Python (LangChain + pgvector)
 * do the indexing, then flips the repo's status when it's done.
 *
 * Fire-and-forget here for simplicity; Python should call back to
 * PATCH /api/repos/{id}/status when indexing finishes (or fails).
 */
@Service
public class IndexingClient {

    private final WebClient webClient;
    private final RepoRepository repoRepository;

    public IndexingClient(
        @Value("${python-service.base-url}") String pythonServiceBaseUrl,
        RepoRepository repoRepository
    ) {
        this.webClient = WebClient.builder().baseUrl(pythonServiceBaseUrl).build();
        this.repoRepository = repoRepository;
    }

    public void triggerIndex(Repo repo) {
        repo.setStatus(IndexStatus.INDEXING);
        repoRepository.save(repo);

        webClient.post()
            .uri("/index")
            .bodyValue(new IndexRequest(repo.getId(), repo.getLocalPath()))
            .retrieve()
            .toBodilessEntity()
            .doOnError(err -> markFailed(repo))
            .subscribe(); // async, non-blocking - don't hold up the HTTP response to the frontend
    }

    private void markFailed(Repo repo) {
        repo.setStatus(IndexStatus.FAILED);
        repoRepository.save(repo);
    }
}
