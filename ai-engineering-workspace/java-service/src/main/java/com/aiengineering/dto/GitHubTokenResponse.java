package com.aiengineering.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("scope") String scope,
    @JsonProperty("token_type") String tokenType
) {}
