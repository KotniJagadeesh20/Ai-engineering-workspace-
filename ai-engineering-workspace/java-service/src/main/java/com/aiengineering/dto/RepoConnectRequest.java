package com.aiengineering.dto;

import jakarta.validation.constraints.NotBlank;
import com.aiengineering.util.GitHubUrl;
import jakarta.validation.constraints.Pattern;

public record RepoConnectRequest(
    @NotBlank(message = "githubUrl is required")
    @Pattern(
        regexp = GitHubUrl.REGEX,
        message = "githubUrl must look like https://github.com/<owner>/<repo>[.git]"
    )
    String githubUrl
) {}
