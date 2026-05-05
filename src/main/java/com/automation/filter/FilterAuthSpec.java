package com.automation.filter;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Optional auth overrides in a filter file.
 *
 * <p>These fields map directly to runtime variable keys used by the executor.
 */
public record FilterAuthSpec(
        @JsonAlias({"username", "API_USERNAME"}) String username,
        @JsonAlias({"password", "API_PASSWORD"}) String password,
        @JsonAlias({"bearerToken", "BEARER_TOKEN"}) String bearerToken,
        @JsonAlias({"apiKey", "API_KEY"}) String apiKey,
        @JsonAlias({"apiKeyHeader", "APIKEY_HEADER"}) String apiKeyHeader
) {
}