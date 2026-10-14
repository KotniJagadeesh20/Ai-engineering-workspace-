package com.aiengineering.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// Python's /index expects snake_case (repo_id, repo_path).
public record IndexRequest(
    @JsonProperty("repo_id") UUID repoId,
    @JsonProperty("repo_path") String repoPath
) {}
