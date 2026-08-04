package com.aiengineering.dto;

import java.util.UUID;

public record IndexRequest(
    UUID repoId,
    String repoPath
) {}
