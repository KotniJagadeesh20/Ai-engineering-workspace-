package com.aiengineering.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUserProfile(
    @JsonProperty("id") Long id,
    @JsonProperty("login") String login,
    @JsonProperty("email") String email,
    @JsonProperty("avatar_url") String avatarUrl,
    @JsonProperty("name") String name
) {}
