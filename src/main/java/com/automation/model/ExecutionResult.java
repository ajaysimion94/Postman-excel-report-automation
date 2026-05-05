package com.automation.model;

import java.time.Instant;
import java.util.List;

public record ExecutionResult(
        String folderPath,
        String requestName,
        String method,
        String url,
        int statusCode,
        long durationMillis,
        boolean success,
        String errorMessage,
        String responseBody,
        String displayBody,
        Instant executedAt,
        List<String> assertions
) {
}