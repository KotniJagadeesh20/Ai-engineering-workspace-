package com.aiengineering.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps a single entry from GitHub's GET /user/repos response.
 * Only the fields we actually use are mapped - GitHub's real payload has
 * dozens more (we ignore the rest via @JsonIgnoreProperties).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoSummary(
    @JsonProperty("id") Long id,
    @JsonProperty("name") String name,
    @JsonProperty("full_name") String fullName,
    @JsonProperty("private") boolean isPrivate,
    @JsonProperty("clone_url") String cloneUrl,
    @JsonProperty("default_branch") String defaultBranch,
    @JsonProperty("language") String language,
    @JsonProperty("stargazers_count") Integer stargazersCount,
    @JsonProperty("updated_at") String updatedAt
) {}
