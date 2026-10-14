package com.aiengineering.service;

import com.aiengineering.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.aiengineering.exception.UpstreamServiceException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Proxies RAG questions to the Python service, but ONLY after the caller
 * (RepoController) has confirmed the requesting user actually has access
 * to this repo. This is the piece that was missing before: without it,
 * anyone who knows a repo_id could call Python's /rag/query directly and
 * bypass permission checks entirely, since Python itself has none by design
 * (see python-service/README.md "Known gaps").
 */
@Service
public class RagQueryClient {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(90);

    private final WebClient webClient;

    public RagQueryClient(@Value("${python-service.base-url}") String pythonServiceBaseUrl,
        @Value("${internal.service-secret}") String internalSecret) {
        this.webClient = WebClient.builder()
            .baseUrl(pythonServiceBaseUrl)
            .defaultHeader("X-Internal-Secret", internalSecret)
            .build();
    }

    public QueryResponse query(UUID repoId, String question) {
        try {
            return webClient.post()
                .uri("/rag/query")
                .bodyValue(Map.of("repo_id", repoId.toString(), "question", question))
                .retrieve()
                .bodyToMono(QueryResponse.class)
                .block(QUERY_TIMEOUT);
        } catch (RuntimeException e) {
            // Timeout, connection failure, or non-2xx from Python.
            throw new UpstreamServiceException("The AI service failed to answer the question", e);
        }
    }
}
