package com.aiengineering.dto;

import jakarta.validation.constraints.NotBlank;

public record RepoConnectRequest(
    @NotBlank(message = "githubUrl is required")
    String githubUrl
) {}
