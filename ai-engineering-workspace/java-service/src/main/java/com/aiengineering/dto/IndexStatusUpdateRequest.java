package com.aiengineering.dto;

import com.aiengineering.entity.IndexStatus;

public record IndexStatusUpdateRequest(
    IndexStatus status,
    String errorMessage // populated only when status == FAILED
) {}
