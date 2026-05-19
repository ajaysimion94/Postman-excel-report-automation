package com.automation.auth.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a named credential profile stored in the encrypted credential store.
 * Sensitive fields (apiPassword, bearerToken, apiKey) are stored encrypted at rest.
 * Non-sensitive display fields (profileName, apiUsername, apiKeyHeader, createdAt) are readable
 * from the decrypted store but never logged or printed directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfile(
        String profileName,
        String apiUsername,
        String apiPassword,
        String bearerToken,
        String apiKey,
        String apiKeyHeader,
        String createdAt
) {
}
