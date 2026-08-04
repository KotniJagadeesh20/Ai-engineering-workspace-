package com.aiengineering.service;

import com.aiengineering.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    private final WebClient webClient;

    public RagQueryClient(@Value("${python-service.base-url}") String pythonServiceBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(pythonServiceBaseUrl).build();
    }

    public QueryResponse query(UUID repoId, String question) {
        return webClient.post()
            .uri("/rag/query")
            .bodyValue(Map.of("repo_id", repoId.toString(), "question", question))
            .retrieve()
            .bodyToMono(QueryResponse.class)
            .block();
    }
}
