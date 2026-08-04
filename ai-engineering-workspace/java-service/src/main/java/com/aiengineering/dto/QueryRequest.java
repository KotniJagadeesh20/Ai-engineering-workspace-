package com.aiengineering.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
    @NotBlank(message = "question is required")
    String question
) {}
