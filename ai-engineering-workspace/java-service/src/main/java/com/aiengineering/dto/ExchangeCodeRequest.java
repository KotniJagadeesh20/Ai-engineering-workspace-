package com.aiengineering.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeCodeRequest(
    @NotBlank(message = "code is required")
    String code
) {}
